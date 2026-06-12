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
        Location depot = new Location("depot", request.depot().lat(), request.depot().lon());
        List<Location> locations = new ArrayList<>();
        locations.add(depot);

        List<Visit> visits = new ArrayList<>();
        int idx = 0;
        for (VisitDto dto : request.visits()) {
            String id = dto.id() != null ? dto.id() : "v" + idx;
            Location loc = new Location("loc-" + id, dto.lat(), dto.lon());
            locations.add(loc);
            visits.add(new Visit(id, dto.name(), loc, dto.resolvedDemand(), dto.resolvedServiceDurationSeconds()));
            idx++;
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

        return toResponse(solution, request, depot);
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

    private OptimizeResponse toResponse(VehicleRoutePlan solution, OptimizeRequest request, Location depot) {
        LocalDateTime departureTime = request.departureTime() != null
                ? request.departureTime() : LocalDateTime.now();
        GeometryFormat geometryFormat = request.resolvedGeometryFormat();

        List<OptimizeResponse.RouteDto> routes = new ArrayList<>();
        long grandTotalDriving = 0;
        double grandTotalDistance = 0;

        for (Vehicle vehicle : solution.getVehicles()) {
            List<OptimizeResponse.StopDto> stops = new ArrayList<>();
            double cumulativeDistance = 0;
            long cumulativeDriving = 0;
            long serviceTotal = 0;
            LocalDateTime clock = departureTime;

            double prevLat = depot.getLat();
            double prevLon = depot.getLon();

            List<double[]> routeGeometry = new ArrayList<>();

            for (Visit visit : vehicle.getVisits()) {
                Location loc = visit.getLocation();
                RoutingEngine.Leg raw = routingEngine.route(prevLat, prevLon, loc.getLat(), loc.getLon());
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
                prevLat = loc.getLat();
                prevLon = loc.getLon();
            }

            OptimizeResponse.LegDto returnLeg;
            LocalDateTime returnTime;
            if (vehicle.getVisits().isEmpty()) {
                returnLeg = new OptimizeResponse.LegDto(0, 0, null, null);
                returnTime = departureTime;
            } else {
                RoutingEngine.Leg rawReturn = routingEngine.route(prevLat, prevLon, depot.getLat(), depot.getLon());
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
        return new OptimizeResponse(score, grandTotalDriving, grandTotalDistance, routes);
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
