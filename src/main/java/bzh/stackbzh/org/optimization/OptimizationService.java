package bzh.stackbzh.org.optimization;

import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverJob;
import ai.timefold.solver.core.api.solver.SolverJobBuilder;
import ai.timefold.solver.core.api.solver.SolverManager;
import bzh.stackbzh.org.notification.DiscordNotifier;
import bzh.stackbzh.org.optimization.domain.Location;
import bzh.stackbzh.org.optimization.domain.StopType;
import bzh.stackbzh.org.optimization.domain.Vehicle;
import bzh.stackbzh.org.optimization.domain.VehicleRoutePlan;
import bzh.stackbzh.org.optimization.domain.Visit;
import bzh.stackbzh.org.optimization.dto.DispatchRequest;
import bzh.stackbzh.org.optimization.dto.DispatchResponse;
import bzh.stackbzh.org.optimization.dto.OptimizeRequest;
import bzh.stackbzh.org.optimization.dto.OptimizeResponse;
import bzh.stackbzh.org.optimization.dto.ShipmentDto;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Les deux acceptent, en plus des arrets simples {@code visits}, des MISSIONS APPAIREES
 * {@code shipments} (chargement -> enlevement). Chaque mission est aplatie en un ou deux points
 * ({@code toSpecs}) dont les {@code Visit} sont relies entre eux : les contraintes dures du solveur
 * garantissent alors meme vehicule et chargement en premier. Une mission dont une extremite n'est pas
 * rattachable au reseau routier est ecartee EN ENTIER (motif {@code PAIRED_POINT_SKIPPED}). Sans
 * {@code shipments}, le comportement est strictement celui d'avant, y compris le budget de resolution.
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
    /** /optimize avec missions appairees : temps de resolution (s) par defaut et plafond. */
    private final int shipmentsDefaultSolvingSeconds;
    private final int shipmentsMaxSolvingSeconds;

    public OptimizationService(MatrixService matrixService,
                               RoutingEngine routingEngine,
                               SolverManager<VehicleRoutePlan, UUID> solverManager,
                               DiscordNotifier notifier,
                               @Value("${app.optimization.max-waiting-seconds:3600}") int defaultMaxWaitingSeconds,
                               @Value("${app.optimization.dispatch.default-solving-seconds:10}") int dispatchDefaultSolvingSeconds,
                               @Value("${app.optimization.dispatch.max-solving-seconds:60}") int dispatchMaxSolvingSeconds,
                               @Value("${app.optimization.shipments.default-solving-seconds:5}") int shipmentsDefaultSolvingSeconds,
                               @Value("${app.optimization.shipments.max-solving-seconds:60}") int shipmentsMaxSolvingSeconds) {
        this.matrixService = matrixService;
        this.routingEngine = routingEngine;
        this.solverManager = solverManager;
        this.notifier = notifier;
        this.defaultMaxWaitingSeconds = defaultMaxWaitingSeconds;
        this.dispatchDefaultSolvingSeconds = Math.max(1, dispatchDefaultSolvingSeconds);
        this.dispatchMaxSolvingSeconds = Math.max(this.dispatchDefaultSolvingSeconds, dispatchMaxSolvingSeconds);
        this.shipmentsDefaultSolvingSeconds = Math.max(1, shipmentsDefaultSolvingSeconds);
        this.shipmentsMaxSolvingSeconds = Math.max(this.shipmentsDefaultSolvingSeconds, shipmentsMaxSolvingSeconds);
    }

    // ------------------------------------------------------------------ /optimize

    public OptimizeResponse optimize(OptimizeRequest request) {
        List<VisitSpec> specs = toSpecs(request.resolvedVisits(), request.resolvedShipments());
        // Sans mission appairee et sans budget demande, on garde la terminaison globale du serveur :
        // le comportement des requetes historiques est inchange, a la seconde pres.
        Integer solvingSeconds = resolveOptimizeSolvingSeconds(request.maxSolvingSeconds(),
                hasPairedShipment(request.resolvedShipments()));
        Prepared p = prepare(request.depot(), specs, request.departureTime(), request.maxWaitingSeconds());
        if (p.visits().isEmpty()) {
            return new OptimizeResponse("n/a", true, 0, 0, p.maxWaiting(), solvingSeconds, 0, 0,
                    new ArrayList<>(), p.skipped());
        }
        VehicleRoutePlan problem = buildProblem(p, request.resolvedVehicleCount(), request.vehicleCapacity(), false);
        VehicleRoutePlan solution = solve(problem,
                solvingSeconds == null ? null : Duration.ofSeconds(solvingSeconds));
        Routes r = buildRoutes(solution, p, request.resolvedGeometryFormat());
        return new OptimizeResponse(r.score(), r.feasible(), r.violations(), r.pairingViolations(), p.maxWaiting(),
                solvingSeconds, r.totalDriving(), r.totalDistance(), r.routes(), p.skipped());
    }

    // ------------------------------------------------------------------ /dispatch

    public DispatchResponse dispatch(DispatchRequest request) {
        int solvingSeconds = resolveSolvingSeconds(request.maxSolvingSeconds());
        int vehicleCount = request.vehicleCount();
        List<VisitSpec> specs = toSpecs(request.resolvedVisits(), request.resolvedShipments());
        Prepared p = prepare(request.depot(), specs, request.departureTime(), request.maxWaitingSeconds());
        if (p.visits().isEmpty()) {
            return new DispatchResponse("n/a", true, 0, 0, p.maxWaiting(), solvingSeconds, vehicleCount, 0, 0, 0, 0,
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

        return new DispatchResponse(r.score(), r.feasible(), r.violations(), r.pairingViolations(), p.maxWaiting(),
                solvingSeconds, vehicleCount, used, r.totalDriving(), r.totalDistance(), totalDuration, balance,
                r.routes(), p.skipped());
    }

    /** Budget de resolution effectif de /dispatch : requete sinon defaut serveur, plafonne par la config. */
    int resolveSolvingSeconds(Integer requested) {
        return resolveSolvingSeconds(requested, dispatchDefaultSolvingSeconds, dispatchMaxSolvingSeconds);
    }

    /**
     * Budget de resolution de /optimize. {@code null} = terminaison globale du serveur
     * ({@code timefold.solver.termination.spent-limit}), c'est-a-dire le comportement historique : c'est le cas
     * d'une tournee sans mission appairee dont la requete ne demande pas de budget particulier. Des qu'il y a
     * des missions, le probleme est plus difficile (l'heuristique de construction ignore la precedence, le
     * solveur doit d'abord la reparer) et un budget plus long est applique.
     */
    Integer resolveOptimizeSolvingSeconds(Integer requested, boolean hasPairedShipments) {
        if (requested == null && !hasPairedShipments) {
            return null;
        }
        return resolveSolvingSeconds(requested, shipmentsDefaultSolvingSeconds, shipmentsMaxSolvingSeconds);
    }

    private static int resolveSolvingSeconds(Integer requested, int defaultSeconds, int maxSeconds) {
        int v = requested != null ? requested : defaultSeconds;
        return Math.max(1, Math.min(v, maxSeconds));
    }

    // ------------------------------------------------------------------ pipeline commun

    /** Donnees preparees avant resolution : depot, visites routables, visites ecartees, origine des temps. */
    private record Prepared(Location depot, List<Visit> visits, Map<String, VisitDto> dtoById,
                            List<OptimizeResponse.SkippedVisitDto> skipped,
                            LocalDateTime departureTime, Integer maxWaiting) {
    }

    /** Sortie commune de la construction des tournees. */
    private record Routes(String score, boolean feasible, int violations, int pairingViolations, long totalDriving,
                          double totalDistance, List<OptimizeResponse.RouteDto> routes) {
    }

    /**
     * Un point a visiter avant verification : son identifiant definitif, son DTO, et son appairage eventuel
     * ({@code shipmentId} / {@code stopType} / identifiant de l'autre extremite, tous null pour un arret simple).
     */
    private record VisitSpec(String id, VisitDto dto, String shipmentId, StopType stopType, String pairedId) {
    }

    /**
     * Aplatit les arrets simples et les missions appairees en une seule liste de points, en attribuant les
     * identifiants definitifs (ceux fournis, sinon auto-generes) et en reliant les deux extremites d'une mission.
     * Une mission a une seule extremite ne cree qu'un point, sans appairage : l'autre bout est le depot, qui
     * encadre deja la tournee.
     */
    private static List<VisitSpec> toSpecs(List<VisitDto> visitDtos, List<ShipmentDto> shipmentDtos) {
        List<VisitSpec> specs = new ArrayList<>();
        int idx = 0;
        for (VisitDto dto : visitDtos) {
            specs.add(new VisitSpec(dto.id() != null ? dto.id() : "v" + idx, dto, null, null, null));
            idx++;
        }
        int shipmentIdx = 0;
        for (ShipmentDto shipment : shipmentDtos) {
            String shipmentId = shipment.id() != null ? shipment.id() : "s" + shipmentIdx;
            shipmentIdx++;
            String pickupId = endpointId(shipment.pickup(), shipmentId, "pickup");
            String deliveryId = endpointId(shipment.delivery(), shipmentId, "delivery");
            if (shipment.pickup() != null) {
                specs.add(new VisitSpec(pickupId, shipment.pickup(), shipmentId, StopType.PICKUP, deliveryId));
            }
            if (shipment.delivery() != null) {
                specs.add(new VisitSpec(deliveryId, shipment.delivery(), shipmentId, StopType.DELIVERY, pickupId));
            }
        }
        if (!shipmentDtos.isEmpty()) {
            checkUniqueIds(specs);
        }
        return specs;
    }

    private static String endpointId(VisitDto endpoint, String shipmentId, String suffix) {
        if (endpoint == null) {
            return null;
        }
        return endpoint.id() != null ? endpoint.id() : shipmentId + "-" + suffix;
    }

    /**
     * Les missions sont reliees PAR IDENTIFIANT et la matrice des temps est indexee par identifiant de
     * location : un doublon relierait la mauvaise extremite et fausserait silencieusement les temps de trajet.
     * Verifie uniquement quand la requete contient des missions, pour ne rien changer aux requetes historiques.
     */
    private static void checkUniqueIds(List<VisitSpec> specs) {
        Set<String> seen = new HashSet<>();
        for (VisitSpec spec : specs) {
            if (!seen.add(spec.id())) {
                throw new IllegalArgumentException("Identifiant de point duplique : '" + spec.id()
                        + "'. Les missions appairees sont reliees par identifiant : chaque visite et chaque "
                        + "extremite de mission doit en avoir un unique.");
            }
        }
    }

    /** true si au moins une mission a REELLEMENT deux extremites (donc impose un ordre au solveur). */
    private static boolean hasPairedShipment(List<ShipmentDto> shipments) {
        return shipments.stream().anyMatch(ShipmentDto::isPaired);
    }

    private Prepared prepare(Coordinate depotCoord, List<VisitSpec> specs,
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

        // Passe 1 : rattachement de chaque point au reseau routier.
        Map<String, VisitSpec> routable = new LinkedHashMap<>();
        Map<String, OptimizeResponse.SkippedVisitDto> skippedById = new LinkedHashMap<>();
        for (VisitSpec spec : specs) {
            RoutingEngine.PointCheck check = routingEngine.checkPoint(spec.dto().lat(), spec.dto().lon());
            if (check.status() == RoutingEngine.PointStatus.OK) {
                routable.put(spec.id(), spec);
            } else {
                skippedById.put(spec.id(), toSkipped(spec, check));
            }
        }

        // Passe 2 : une mission est ecartee EN ENTIER ou pas du tout. Garder une extremite orpheline
        // rendrait la contrainte dure d'appairage insatisfiable et toute la tournee infaisable.
        for (VisitSpec spec : specs) {
            if (spec.pairedId() != null && skippedById.containsKey(spec.pairedId())
                    && routable.remove(spec.id()) != null) {
                skippedById.put(spec.id(), newSkipped(spec, "PAIRED_POINT_SKIPPED", null));
            }
        }

        // Passe 3 : construction des visites retenues, puis cablage croise des paires.
        List<Visit> visits = new ArrayList<>();
        Map<String, VisitDto> dtoById = new HashMap<>();
        Map<String, Visit> visitById = new HashMap<>();
        for (VisitSpec spec : routable.values()) {
            VisitDto dto = spec.dto();
            Location loc = new Location("loc-" + spec.id(), dto.lat(), dto.lon());
            Visit visit = new Visit(spec.id(), dto.name(), loc, dto.resolvedDemand(),
                    dto.resolvedServiceDurationSeconds(),
                    offsetSeconds(departureTime, dto.timeWindowStart()),
                    offsetSeconds(departureTime, dto.timeWindowEnd()),
                    maxWaitingLong);
            visit.setShipment(spec.shipmentId(), spec.stopType());
            dtoById.put(spec.id(), dto);
            visitById.put(spec.id(), visit);
            visits.add(visit);
        }
        for (VisitSpec spec : routable.values()) {
            if (spec.pairedId() != null) {
                visitById.get(spec.id()).setPairedVisit(visitById.get(spec.pairedId()));
            }
        }

        List<OptimizeResponse.SkippedVisitDto> skipped = new ArrayList<>(skippedById.values());
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

    private static OptimizeResponse.SkippedVisitDto toSkipped(VisitSpec spec, RoutingEngine.PointCheck check) {
        String reason = check.status() == RoutingEngine.PointStatus.TOO_FAR ? "TOO_FAR" : "UNROUTABLE";
        Double distance = Double.isNaN(check.snapDistanceMeters()) ? null : check.snapDistanceMeters();
        return newSkipped(spec, reason, distance);
    }

    private static OptimizeResponse.SkippedVisitDto newSkipped(VisitSpec spec, String reason, Double distance) {
        VisitDto dto = spec.dto();
        return new OptimizeResponse.SkippedVisitDto(spec.id(), dto.name(), dto.lat(), dto.lon(), reason, distance,
                spec.shipmentId(), spec.stopType() == null ? null : spec.stopType().name());
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
                        status, visit.getDemand(), visit.getShipmentId(),
                        visit.getStopType() == null ? null : visit.getStopType().name());
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

        int pairingViolations = countPairingViolations(solution);
        boolean hardOk = solution.getScore() != null && solution.getScore().hardScore() == 0;
        boolean feasible = hardOk && violations == 0 && pairingViolations == 0;
        String score = solution.getScore() != null ? solution.getScore().toString() : "n/a";
        return new Routes(score, feasible, violations, pairingViolations, grandTotalDriving, grandTotalDistance,
                routes);
    }

    /**
     * Missions appairees cassees dans la solution renvoyee, recomptees sur l'ordre reel des tournees
     * (independamment du score) : en parcourant une tournee, un enlevement dont le chargement n'a pas encore
     * ete rencontre signale soit une extremite restee sur un autre vehicule, soit un ordre inverse.
     * Les missions a une seule extremite ne comptent jamais : elles n'imposent aucun ordre.
     */
    private static int countPairingViolations(VehicleRoutePlan solution) {
        int violations = 0;
        for (Vehicle vehicle : solution.getVehicles()) {
            Set<String> loaded = new HashSet<>();
            for (Visit visit : vehicle.getVisits()) {
                if (visit.isPickup()) {
                    loaded.add(visit.getShipmentId());
                } else if (visit.isDelivery() && visit.isPaired() && !loaded.contains(visit.getShipmentId())) {
                    violations++;
                }
            }
        }
        return violations;
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
