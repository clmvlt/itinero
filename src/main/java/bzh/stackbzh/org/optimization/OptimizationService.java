package bzh.stackbzh.org.optimization;

import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverJob;
import ai.timefold.solver.core.api.solver.SolverJobBuilder;
import ai.timefold.solver.core.api.solver.SolverManager;
import bzh.stackbzh.org.notification.DiscordNotifier;
import bzh.stackbzh.org.optimization.domain.Location;
import bzh.stackbzh.org.optimization.domain.Vehicle;
import bzh.stackbzh.org.optimization.domain.VehicleRoutePlan;
import bzh.stackbzh.org.optimization.domain.Visit;
import bzh.stackbzh.org.optimization.dto.DispatchRequest;
import bzh.stackbzh.org.optimization.dto.DispatchResponse;
import bzh.stackbzh.org.optimization.dto.OptimizeRequest;
import bzh.stackbzh.org.optimization.dto.OptimizeResponse;
import bzh.stackbzh.org.optimization.dto.VisitDto;
import bzh.stackbzh.org.routing.GeometryEncoder;
import bzh.stackbzh.org.routing.MatrixService;
import bzh.stackbzh.org.routing.RoutingEngine;
import bzh.stackbzh.org.routing.dto.Coordinate;
import bzh.stackbzh.org.routing.dto.GeometryFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

/**
 * Deux modes partagent le meme pipeline (verification des points, matrice de temps, solveur, reponse enrichie) :
 * <ul>
 *   <li>{@link #optimize(OptimizeRequest)} : ordre de passage optimal, objectif = temps total minimal
 *   (avec plusieurs vehicules sans capacite, tout finit sur un seul vehicule) ; temps de resolution =
 *   {@code timefold.solver.termination.spent-limit}.</li>
 *   <li>{@link #dispatch(DispatchRequest)} : repartition de N points en K tournees EQUILIBREES en duree
 *   (vehicules {@code balanceDuration = true} -> contrainte souple {@code balanceTourDurations}) ; temps de
 *   resolution choisi par requete ({@code maxSolvingSeconds}, borne par la config), applique via
 *   {@link SolverConfigOverride} sans toucher a la configuration globale du {@link SolverManager}.</li>
 * </ul>
 */
@Service
public class OptimizationService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationService.class);
    private static final int UNLIMITED_CAPACITY = Integer.MAX_VALUE / 2;

    private final MatrixService matrixService;
    private final RoutingEngine routingEngine;
    private final SolverManager<VehicleRoutePlan, UUID> solverManager;
    private final DiscordNotifier notifier;
    /** Attente max (s) par defaut devant une fenetre horaire ; <= 0 = illimitee. */
    private final int defaultMaxWaitingSeconds;
    /** /dispatch : temps de resolution (s) par defaut et plafond. */
    private final int dispatchDefaultSolvingSeconds;
    private final int dispatchMaxSolvingSeconds;

    public OptimizationService(MatrixService matrixService,
                               RoutingEngine routingEngine,
                               SolverManager<VehicleRoutePlan, UUID> solverManager,
                               DiscordNotifier notifier,
                               @Value("${app.optimization.max-waiting-seconds:3600}") int defaultMaxWaitingSeconds,
                               @Value("${app.optimization.dispatch.default-solving-seconds:10}") int dispatchDefaultSolvingSeconds,
                               @Value("${app.optimization.dispatch.max-solving-seconds:60}") int dispatchMaxSolvingSeconds) {
        this.matrixService = matrixService;
        this.routingEngine = routingEngine;
        this.solverManager = solverManager;
        this.notifier = notifier;
        this.defaultMaxWaitingSeconds = defaultMaxWaitingSeconds;
        this.dispatchDefaultSolvingSeconds = Math.max(1, dispatchDefaultSolvingSeconds);
        this.dispatchMaxSolvingSeconds = Math.max(this.dispatchDefaultSolvingSeconds, dispatchMaxSolvingSeconds);
    }

    // ------------------------------------------------------------------ /optimize

    public OptimizeResponse optimize(OptimizeRequest request) {
        Prepared p = prepare(request.depot(), request.visits(), request.departureTime(), request.maxWaitingSeconds());
        if (p.visits().isEmpty()) {
            return new OptimizeResponse("n/a", true, 0, p.maxWaiting(), 0, 0, new ArrayList<>(), p.skipped());
        }
        VehicleRoutePlan problem = buildProblem(p, request.resolvedVehicleCount(), request.vehicleCapacity(), false);
        VehicleRoutePlan solution = solve(problem, null);
        Routes r = buildRoutes(solution, p, request.resolvedGeometryFormat());
        return new OptimizeResponse(r.score(), r.feasible(), r.violations(), p.maxWaiting(),
                r.totalDriving(), r.totalDistance(), r.routes(), p.skipped());
    }

    // ------------------------------------------------------------------ /dispatch

    public DispatchResponse dispatch(DispatchRequest request) {
        int solvingSeconds = resolveSolvingSeconds(request.maxSolvingSeconds());
        int vehicleCount = request.vehicleCount();
        Prepared p = prepare(request.depot(), request.visits(), request.departureTime(), request.maxWaitingSeconds());
        if (p.visits().isEmpty()) {
            return new DispatchResponse("n/a", true, 0, p.maxWaiting(), solvingSeconds, vehicleCount, 0, 0, 0, 0,
                    new DispatchResponse.BalanceDto(0, 0, 0, 0), new ArrayList<>(), p.skipped());
        }
        VehicleRoutePlan problem = buildProblem(p, vehicleCount, request.vehicleCapacity(), true);
        long t0 = System.currentTimeMillis();
        VehicleRoutePlan solution = solve(problem, Duration.ofSeconds(solvingSeconds));
        log.info("Dispatch : {} visites reparties en {} tournees en {} ms (budget {} s), score {}",
                p.visits().size(), vehicleCount, System.currentTimeMillis() - t0, solvingSeconds, solution.getScore());
        Routes r = buildRoutes(solution, p, request.resolvedGeometryFormat());

        long longest = 0;
        long shortest = Long.MAX_VALUE;
        long sumUsed = 0;
        long totalDuration = 0;
        int used = 0;
        for (OptimizeResponse.RouteDto route : r.routes()) {
            totalDuration += route.durationSeconds();
            if (route.stops().isEmpty()) {
                continue;
            }
            used++;
            sumUsed += route.durationSeconds();
            longest = Math.max(longest, route.durationSeconds());
            shortest = Math.min(shortest, route.durationSeconds());
        }
        DispatchResponse.BalanceDto balance = used == 0
                ? new DispatchResponse.BalanceDto(0, 0, 0, 0)
                : new DispatchResponse.BalanceDto(longest, shortest, Math.round((double) sumUsed / used), longest - shortest);

        return new DispatchResponse(r.score(), r.feasible(), r.violations(), p.maxWaiting(), solvingSeconds,
                vehicleCount, used, r.totalDriving(), r.totalDistance(), totalDuration, balance, r.routes(), p.skipped());
    }

    /** Budget de resolution effectif : requete sinon defaut serveur, plafonne par la config. */
    int resolveSolvingSeconds(Integer requested) {
        int v = requested != null ? requested : dispatchDefaultSolvingSeconds;
        return Math.max(1, Math.min(v, dispatchMaxSolvingSeconds));
    }

    // ------------------------------------------------------------------ pipeline commun

    /** Donnees preparees avant resolution : depot, visites routables, visites ecartees, origine des temps. */
    private record Prepared(Location depot, List<Visit> visits, Map<String, VisitDto> dtoById,
                            List<OptimizeResponse.SkippedVisitDto> skipped,
                            LocalDateTime departureTime, Integer maxWaiting) {
    }

    /** Sortie commune de la construction des tournees. */
    private record Routes(String score, boolean feasible, int violations, long totalDriving, double totalDistance,
                          List<OptimizeResponse.RouteDto> routes) {
    }

    private Prepared prepare(Coordinate depotCoord, List<VisitDto> visitDtos,
                             LocalDateTime requestedDeparture, Integer requestedMaxWaiting) {
        RoutingEngine.PointCheck depotCheck = routingEngine.checkPoint(depotCoord.lat(), depotCoord.lon());
        if (depotCheck.status() != RoutingEngine.PointStatus.OK) {
            String message = depotMessage(depotCheck);
            notifier.notifyError("Optimisation refusee : depot non rattachable (400)",
                    message + "\nDepot : (" + depotCoord.lat() + ", " + depotCoord.lon() + ")");
            throw new IllegalArgumentException(message);
        }

        // Origine des temps de la tournee : heure de depart estimee (ou maintenant). Toutes les
        // fenetres horaires sont converties en offsets (secondes) par rapport a cet instant.
        LocalDateTime departureTime = resolveDepartureTime(requestedDeparture);
        // Attente max toleree devant une fenetre : requete, sinon defaut serveur ; 0 = desactive (null).
        Integer maxWaiting = resolveMaxWaitingSeconds(requestedMaxWaiting);
        Long maxWaitingLong = maxWaiting == null ? null : maxWaiting.longValue();

        Location depot = new Location("depot", depotCoord.lat(), depotCoord.lon());
        List<Visit> visits = new ArrayList<>();
        Map<String, VisitDto> dtoById = new HashMap<>();
        List<OptimizeResponse.SkippedVisitDto> skipped = new ArrayList<>();
        int idx = 0;
        for (VisitDto dto : visitDtos) {
            String id = dto.id() != null ? dto.id() : "v" + idx;
            idx++;
            RoutingEngine.PointCheck check = routingEngine.checkPoint(dto.lat(), dto.lon());
            if (check.status() != RoutingEngine.PointStatus.OK) {
                skipped.add(toSkipped(id, dto, check));
                continue;
            }
            Location loc = new Location("loc-" + id, dto.lat(), dto.lon());
            dtoById.put(id, dto);
            visits.add(new Visit(id, dto.name(), loc, dto.resolvedDemand(), dto.resolvedServiceDurationSeconds(),
                    offsetSeconds(departureTime, dto.timeWindowStart()),
                    offsetSeconds(departureTime, dto.timeWindowEnd()),
                    maxWaitingLong));
        }

        if (!skipped.isEmpty()) {
            notifier.notifyError("Optimisation : " + skipped.size() + " visite(s) ecartee(s)",
                    skippedDetails(skipped));
        }
        return new Prepared(depot, visits, dtoById, skipped, departureTime, maxWaiting);
    }

    /** Calcule la matrice des temps (depot + visites), l'injecte dans les locations et cree les vehicules. */
    private VehicleRoutePlan buildProblem(Prepared p, int vehicleCount, Integer vehicleCapacity, boolean balance) {
        List<Location> locations = new ArrayList<>();
        locations.add(p.depot());
        for (Visit visit : p.visits()) {
            locations.add(visit.getLocation());
        }
        List<double[]> coords = locations.stream()
                .map(l -> new double[]{l.getLat(), l.getLon()})
                .toList();
        long[][] matrix = matrixService.timeMatrixSeconds(coords);
        for (int i = 0; i < locations.size(); i++) {
            Map<String, Long> times = new HashMap<>();
            for (int j = 0; j < locations.size(); j++) {
                times.put(locations.get(j).getId(), matrix[i][j]);
            }
            locations.get(i).setDrivingTimeSeconds(times);
        }

        int capacity = vehicleCapacity != null ? vehicleCapacity : UNLIMITED_CAPACITY;
        List<Vehicle> vehicles = new ArrayList<>();
        for (int v = 0; v < Math.max(1, vehicleCount); v++) {
            vehicles.add(new Vehicle("vehicle-" + v, capacity, p.depot(), 0L, balance));
        }
        return new VehicleRoutePlan(vehicles, p.visits());
    }

    /** Limite d'attente effective : valeur de la requete, sinon defaut serveur ; null si desactivee (<= 0). */
    private Integer resolveMaxWaitingSeconds(Integer requested) {
        int v = requested != null ? requested : defaultMaxWaitingSeconds;
        return v <= 0 ? null : v;
    }

    /** Heure de depart de la tournee, tronquee a la seconde (les offsets sont en secondes entieres). */
    private static LocalDateTime resolveDepartureTime(LocalDateTime requested) {
        LocalDateTime t = requested != null ? requested : LocalDateTime.now();
        return t.truncatedTo(ChronoUnit.SECONDS);
    }

    /** Offset (s) d'un instant par rapport a l'origine ; null si l'instant est absent. Peut etre negatif. */
    private static Long offsetSeconds(LocalDateTime origin, LocalDateTime instant) {
        return instant == null ? null : Duration.between(origin, instant).getSeconds();
    }

    private static String depotMessage(RoutingEngine.PointCheck check) {
        if (check.status() == RoutingEngine.PointStatus.TOO_FAR) {
            return "Depot trop eloigne du reseau routier ("
                    + Math.round(check.snapDistanceMeters())
                    + " m de la route la plus proche). Optimisation impossible.";
        }
        return "Depot non routable : aucune route a proximite (coordonnees en mer, "
                + "hors de la zone couverte, ou reseau deconnecte). Optimisation impossible.";
    }

    private static String skippedDetails(List<OptimizeResponse.SkippedVisitDto> skipped) {
        StringBuilder sb = new StringBuilder(
                "Points non rattachables au reseau routier, exclus de la tournee :\n");
        for (OptimizeResponse.SkippedVisitDto s : skipped) {
            sb.append("- ").append(s.name() != null ? s.name() : s.visitId())
                    .append(" (").append(s.lat()).append(", ").append(s.lon()).append(") : ")
                    .append(s.reason());
            if (s.snapDistanceMeters() != null) {
                sb.append(" — route la plus proche a ")
                        .append(Math.round(s.snapDistanceMeters())).append(" m");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static OptimizeResponse.SkippedVisitDto toSkipped(String id, VisitDto dto, RoutingEngine.PointCheck check) {
        String reason = check.status() == RoutingEngine.PointStatus.TOO_FAR ? "TOO_FAR" : "UNROUTABLE";
        Double distance = Double.isNaN(check.snapDistanceMeters()) ? null : check.snapDistanceMeters();
        return new OptimizeResponse.SkippedVisitDto(id, dto.name(), dto.lat(), dto.lon(), reason, distance);
    }

    /**
     * Resout le probleme. {@code spentLimit} null = terminaison de la configuration globale
     * ({@code timefold.solver.termination.spent-limit}) ; sinon la limite est surchargee pour CE job uniquement.
     */
    private VehicleRoutePlan solve(VehicleRoutePlan problem, Duration spentLimit) {
        UUID problemId = UUID.randomUUID();
        SolverJobBuilder<VehicleRoutePlan, UUID> builder = solverManager.solveBuilder()
                .withProblemId(problemId)
                .withProblem(problem);
        if (spentLimit != null) {
            builder.withConfigOverride(new SolverConfigOverride<VehicleRoutePlan>()
                    .withTerminationSpentLimit(spentLimit));
        }
        SolverJob<VehicleRoutePlan, UUID> job = builder.run();
        try {
            return job.getFinalBestSolution();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Optimisation interrompue", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Echec de l'optimisation", e);
        }
    }

    private Routes buildRoutes(VehicleRoutePlan solution, Prepared p, GeometryFormat geometryFormat) {
        LocalDateTime departureTime = p.departureTime();
        Integer maxWaiting = p.maxWaiting();
        Location depot = p.depot();
        Map<String, VisitDto> dtoById = p.dtoById();

        List<OptimizeResponse.RouteDto> routes = new ArrayList<>();
        int violations = 0;
        long grandTotalDriving = 0;
        double grandTotalDistance = 0;

        for (Vehicle vehicle : solution.getVehicles()) {
            List<Visit> vehicleVisits = vehicle.getVisits();
            RoutingEngine.Leg[] rawLegs = routeVehicleLegs(vehicleVisits, depot);

            List<OptimizeResponse.StopDto> stops = new ArrayList<>();
            double cumulativeDistance = 0;
            long cumulativeDriving = 0;
            long serviceTotal = 0;
            long waitingTotal = 0;
            LocalDateTime clock = departureTime;

            List<double[]> routeGeometry = new ArrayList<>();

            for (int k = 0; k < vehicleVisits.size(); k++) {
                Visit visit = vehicleVisits.get(k);
                Location loc = visit.getLocation();
                VisitDto dto = dtoById.get(visit.getId());
                RoutingEngine.Leg raw = rawLegs[k];
                OptimizeResponse.LegDto leg = toLegDto(raw, geometryFormat);
                appendGeometry(routeGeometry, raw.geometry());

                // Heures reelles (durees de trajet issues du routage segment par segment) avec la
                // meme regle que le solveur : attente si en avance sur la fenetre, retard si au-dela.
                LocalDateTime windowStart = dto != null ? dto.timeWindowStart() : null;
                LocalDateTime windowEnd = dto != null ? dto.timeWindowEnd() : null;
                LocalDateTime arrival = clock.plusSeconds(leg.durationSeconds());
                LocalDateTime serviceStart = windowStart != null && arrival.isBefore(windowStart) ? windowStart : arrival;
                long waiting = Duration.between(arrival, serviceStart).getSeconds();
                long late = windowEnd != null && arrival.isAfter(windowEnd)
                        ? Duration.between(windowEnd, arrival).getSeconds() : 0;
                long excessiveWaiting = maxWaiting != null ? Math.max(0, waiting - maxWaiting) : 0;
                String status = late > 0 ? "LATE" : excessiveWaiting > 0 ? "WAITING_TOO_LONG" : "OK";
                int service = visit.getServiceDurationSeconds();
                LocalDateTime departure = serviceStart.plusSeconds(service);

                cumulativeDistance += leg.distanceMeters();
                cumulativeDriving += leg.durationSeconds();
                serviceTotal += service;
                waitingTotal += waiting;

                OptimizeResponse.StopDto stop = new OptimizeResponse.StopDto(
                        visit.getId(), visit.getName(), loc.getLat(), loc.getLon(),
                        leg, cumulativeDistance, cumulativeDriving,
                        arrival, serviceStart, departure, windowStart, windowEnd, waiting, excessiveWaiting, late,
                        status, visit.getDemand());
                stops.add(stop);
                if (!"OK".equals(status)) {
                    violations++;
                }

                clock = departure;
            }

            OptimizeResponse.LegDto returnLeg;
            LocalDateTime returnTime;
            if (vehicleVisits.isEmpty()) {
                returnLeg = new OptimizeResponse.LegDto(0, 0, null, null);
                returnTime = departureTime;
            } else {
                RoutingEngine.Leg rawReturn = rawLegs[vehicleVisits.size()];
                returnLeg = toLegDto(rawReturn, geometryFormat);
                appendGeometry(routeGeometry, rawReturn.geometry());
                cumulativeDistance += returnLeg.distanceMeters();
                cumulativeDriving += returnLeg.durationSeconds();
                returnTime = clock.plusSeconds(returnLeg.durationSeconds());
            }

            grandTotalDriving += cumulativeDriving;
            grandTotalDistance += cumulativeDistance;

            double[][] routePoints = geometryFormat == GeometryFormat.POINTS
                    ? routeGeometry.toArray(new double[0][]) : null;
            String routePolyline = geometryFormat == GeometryFormat.NONE
                    ? null : GeometryEncoder.encodePolyline(routeGeometry.toArray(new double[0][]));

            routes.add(new OptimizeResponse.RouteDto(
                    vehicle.getId(), departureTime, returnTime,
                    cumulativeDriving, serviceTotal, waitingTotal,
                    cumulativeDriving + serviceTotal + waitingTotal,
                    cumulativeDistance, vehicle.getTotalDemand(), stops, returnLeg,
                    routePoints, routePolyline));
        }

        boolean hardOk = solution.getScore() != null && solution.getScore().hardScore() == 0;
        boolean feasible = hardOk && violations == 0;
        String score = solution.getScore() != null ? solution.getScore().toString() : "n/a";
        return new Routes(score, feasible, violations, grandTotalDriving, grandTotalDistance, routes);
    }

    private RoutingEngine.Leg[] routeVehicleLegs(List<Visit> visits, Location depot) {
        if (visits.isEmpty()) {
            return new RoutingEngine.Leg[0];
        }
        int legCount = visits.size() + 1;
        double[][] from = new double[legCount][];
        double[][] to = new double[legCount][];

        Location first = visits.get(0).getLocation();
        from[0] = new double[]{depot.getLat(), depot.getLon()};
        to[0] = new double[]{first.getLat(), first.getLon()};
        for (int k = 1; k < visits.size(); k++) {
            Location prev = visits.get(k - 1).getLocation();
            Location cur = visits.get(k).getLocation();
            from[k] = new double[]{prev.getLat(), prev.getLon()};
            to[k] = new double[]{cur.getLat(), cur.getLon()};
        }
        Location last = visits.get(visits.size() - 1).getLocation();
        from[legCount - 1] = new double[]{last.getLat(), last.getLon()};
        to[legCount - 1] = new double[]{depot.getLat(), depot.getLon()};

        RoutingEngine.Leg[] legs = new RoutingEngine.Leg[legCount];
        IntStream.range(0, legCount).parallel().forEach(k ->
                legs[k] = routingEngine.route(from[k][0], from[k][1], to[k][0], to[k][1]));
        return legs;
    }

    private static OptimizeResponse.LegDto toLegDto(RoutingEngine.Leg leg, GeometryFormat format) {
        double[][] points = format == GeometryFormat.POINTS ? leg.geometry() : null;
        String polyline = format == GeometryFormat.POLYLINE ? GeometryEncoder.encodePolyline(leg.geometry()) : null;
        return new OptimizeResponse.LegDto(leg.distanceMeters(), leg.durationSeconds(), points, polyline);
    }

    private static void appendGeometry(List<double[]> accumulator, double[][] segment) {
        if (segment == null) {
            return;
        }
        int start = accumulator.isEmpty() ? 0 : 1;
        for (int i = start; i < segment.length; i++) {
            accumulator.add(segment[i]);
        }
    }
}
