package bzh.stackbzh.org.optimization;

import ai.timefold.solver.core.api.solver.SolverJob;
import ai.timefold.solver.core.api.solver.SolverManager;
import bzh.stackbzh.org.optimization.domain.Location;
import bzh.stackbzh.org.optimization.domain.Vehicle;
import bzh.stackbzh.org.optimization.domain.VehicleRoutePlan;
import bzh.stackbzh.org.optimization.domain.Visit;
import bzh.stackbzh.org.optimization.dto.OptimizeRequest;
import bzh.stackbzh.org.optimization.dto.OptimizeResponse;
import bzh.stackbzh.org.optimization.dto.VisitDto;
import bzh.stackbzh.org.routing.GeometryEncoder;
import bzh.stackbzh.org.routing.MatrixService;
import bzh.stackbzh.org.routing.RoutingEngine;
import bzh.stackbzh.org.routing.dto.GeometryFormat;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

@Service
public class OptimizationService {

    private static final int UNLIMITED_CAPACITY = Integer.MAX_VALUE / 2;

    private final MatrixService matrixService;
    private final RoutingEngine routingEngine;
    private final SolverManager<VehicleRoutePlan, UUID> solverManager;

    public OptimizationService(MatrixService matrixService,
                               RoutingEngine routingEngine,
                               SolverManager<VehicleRoutePlan, UUID> solverManager) {
        this.matrixService = matrixService;
        this.routingEngine = routingEngine;
        this.solverManager = solverManager;
    }

    public OptimizeResponse optimize(OptimizeRequest request) {
        RoutingEngine.PointCheck depotCheck =
                routingEngine.checkPoint(request.depot().lat(), request.depot().lon());
        if (depotCheck.status() != RoutingEngine.PointStatus.OK) {
            throw new IllegalArgumentException(depotMessage(depotCheck));
        }

        Location depot = new Location("depot", request.depot().lat(), request.depot().lon());
        List<Location> locations = new ArrayList<>();
        locations.add(depot);

        List<Visit> visits = new ArrayList<>();
        List<OptimizeResponse.SkippedVisitDto> skipped = new ArrayList<>();
        int idx = 0;
        for (VisitDto dto : request.visits()) {
            String id = dto.id() != null ? dto.id() : "v" + idx;
            idx++;
            RoutingEngine.PointCheck check = routingEngine.checkPoint(dto.lat(), dto.lon());
            if (check.status() != RoutingEngine.PointStatus.OK) {
                skipped.add(toSkipped(id, dto, check));
                continue;
            }
            Location loc = new Location("loc-" + id, dto.lat(), dto.lon());
            locations.add(loc);
            visits.add(new Visit(id, dto.name(), loc, dto.resolvedDemand(), dto.resolvedServiceDurationSeconds()));
        }

        if (visits.isEmpty()) {
            return new OptimizeResponse("n/a", 0, 0, new ArrayList<>(), skipped);
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

        int capacity = request.vehicleCapacity() != null ? request.vehicleCapacity() : UNLIMITED_CAPACITY;
        List<Vehicle> vehicles = new ArrayList<>();
        for (int v = 0; v < request.resolvedVehicleCount(); v++) {
            vehicles.add(new Vehicle("vehicle-" + v, capacity, depot));
        }

        VehicleRoutePlan problem = new VehicleRoutePlan(vehicles, visits);
        VehicleRoutePlan solution = solve(problem);

        return toResponse(solution, request, depot, skipped);
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

    private static OptimizeResponse.SkippedVisitDto toSkipped(String id, VisitDto dto, RoutingEngine.PointCheck check) {
        String reason = check.status() == RoutingEngine.PointStatus.TOO_FAR ? "TOO_FAR" : "UNROUTABLE";
        Double distance = Double.isNaN(check.snapDistanceMeters()) ? null : check.snapDistanceMeters();
        return new OptimizeResponse.SkippedVisitDto(id, dto.name(), dto.lat(), dto.lon(), reason, distance);
    }

    private VehicleRoutePlan solve(VehicleRoutePlan problem) {
        UUID problemId = UUID.randomUUID();
        SolverJob<VehicleRoutePlan, UUID> job = solverManager.solve(problemId, problem);
        try {
            return job.getFinalBestSolution();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Optimisation interrompue", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Echec de l'optimisation", e);
        }
    }

    private OptimizeResponse toResponse(VehicleRoutePlan solution, OptimizeRequest request, Location depot,
                                        List<OptimizeResponse.SkippedVisitDto> skipped) {
        LocalDateTime departureTime = request.departureTime() != null
                ? request.departureTime() : LocalDateTime.now();
        GeometryFormat geometryFormat = request.resolvedGeometryFormat();

        List<OptimizeResponse.RouteDto> routes = new ArrayList<>();
        long grandTotalDriving = 0;
        double grandTotalDistance = 0;

        for (Vehicle vehicle : solution.getVehicles()) {
            List<Visit> vehicleVisits = vehicle.getVisits();
            RoutingEngine.Leg[] rawLegs = routeVehicleLegs(vehicleVisits, depot);

            List<OptimizeResponse.StopDto> stops = new ArrayList<>();
            double cumulativeDistance = 0;
            long cumulativeDriving = 0;
            long serviceTotal = 0;
            LocalDateTime clock = departureTime;

            List<double[]> routeGeometry = new ArrayList<>();

            for (int k = 0; k < vehicleVisits.size(); k++) {
                Visit visit = vehicleVisits.get(k);
                Location loc = visit.getLocation();
                RoutingEngine.Leg raw = rawLegs[k];
                OptimizeResponse.LegDto leg = toLegDto(raw, geometryFormat);
                appendGeometry(routeGeometry, raw.geometry());

                LocalDateTime arrival = clock.plusSeconds(leg.durationSeconds());
                int service = visit.getServiceDurationSeconds();
                LocalDateTime departure = arrival.plusSeconds(service);

                cumulativeDistance += leg.distanceMeters();
                cumulativeDriving += leg.durationSeconds();
                serviceTotal += service;

                stops.add(new OptimizeResponse.StopDto(
                        visit.getId(), visit.getName(), loc.getLat(), loc.getLon(),
                        leg, cumulativeDistance, cumulativeDriving, arrival, departure, visit.getDemand()));

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
                    cumulativeDriving, serviceTotal, cumulativeDistance,
                    vehicle.getTotalDemand(), stops, returnLeg,
                    routePoints, routePolyline));
        }

        String score = solution.getScore() != null ? solution.getScore().toString() : "n/a";
        return new OptimizeResponse(score, grandTotalDriving, grandTotalDistance, routes, skipped);
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
