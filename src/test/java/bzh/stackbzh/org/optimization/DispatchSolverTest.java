package bzh.stackbzh.org.optimization;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import bzh.stackbzh.org.optimization.domain.Location;
import bzh.stackbzh.org.optimization.domain.Vehicle;
import bzh.stackbzh.org.optimization.domain.VehicleRoutePlan;
import bzh.stackbzh.org.optimization.domain.Visit;
import bzh.stackbzh.org.optimization.solver.VehicleRoutingConstraintProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valide le mode « dispatch » (repartition equilibree de N points en K tournees) sur une matrice synthetique,
 * sans Spring ni GraphHopper : des clusters geographiques nets autour d'un depot central, temps = distance
 * euclidienne x 100 s. Verifie aussi la surcharge du temps de resolution par job ({@link SolverConfigOverride}).
 */
class DispatchSolverTest {

    private static final int UNLIMITED = Integer.MAX_VALUE / 2;

    /**
     * Depot en (0,0) et {@code clusters} groupes de {@code perCluster} points, chaque groupe a ~1000 s du depot
     * dans une direction differente, les points d'un groupe a ~100 s les uns des autres. Chaque location porte
     * un id "c<cluster>-<i>" pour verifier la coherence geographique des tournees.
     */
    private static List<Location> buildClusters(int clusters, int perCluster) {
        List<Location> locs = new ArrayList<>();
        locs.add(new Location("depot", 0, 0));
        for (int c = 0; c < clusters; c++) {
            double angle = 2 * Math.PI * c / clusters;
            double cx = 10 * Math.cos(angle);
            double cy = 10 * Math.sin(angle);
            for (int i = 0; i < perCluster; i++) {
                double dx = ((i % 4) - 1.5) * 0.6;
                double dy = ((i / 4) - 0.5) * 0.6;
                locs.add(new Location("c" + c + "-" + i, cx + dx, cy + dy));
            }
        }
        for (Location a : locs) {
            Map<String, Long> times = new HashMap<>();
            for (Location b : locs) {
                double d = Math.hypot(a.getLat() - b.getLat(), a.getLon() - b.getLon());
                times.put(b.getId(), Math.round(d * 100));
            }
            a.setDrivingTimeSeconds(times);
        }
        return locs;
    }

    private static VehicleRoutePlan problem(List<Location> locs, int vehicleCount, boolean balance) {
        List<Vehicle> vehicles = new ArrayList<>();
        for (int v = 0; v < vehicleCount; v++) {
            vehicles.add(new Vehicle("vehicle-" + v, UNLIMITED, locs.get(0), 0L, balance));
        }
        List<Visit> visits = new ArrayList<>();
        for (int i = 1; i < locs.size(); i++) {
            visits.add(new Visit(locs.get(i).getId(), null, locs.get(i), 0, 0));
        }
        return new VehicleRoutePlan(vehicles, visits);
    }

    private static SolverConfig config(EnvironmentMode mode, int seconds) {
        return new SolverConfig()
                .withSolutionClass(VehicleRoutePlan.class)
                .withEntityClasses(Vehicle.class, Visit.class)
                .withConstraintProviderClass(VehicleRoutingConstraintProvider.class)
                .withEnvironmentMode(mode)
                .withTerminationSpentLimit(Duration.ofSeconds(seconds));
    }

    private static VehicleRoutePlan solve(VehicleRoutePlan p, EnvironmentMode mode, int seconds) {
        Solver<VehicleRoutePlan> solver = SolverFactory.<VehicleRoutePlan>create(config(mode, seconds)).buildSolver();
        return solver.solve(p);
    }

    /** Cluster d'une visite = prefixe "c<n>" de son id. */
    private static Set<String> clustersOf(Vehicle v) {
        Set<String> set = new HashSet<>();
        for (Visit visit : v.getVisits()) {
            set.add(visit.getId().substring(0, visit.getId().indexOf('-')));
        }
        return set;
    }

    @Test
    void sansEquilibrageToutFinitSurUnSeulVehicule() {
        // Comportement de /optimize : minimiser le temps total => un seul vehicule (chaque vehicule
        // supplementaire ajoute un aller-retour au depot).
        List<Location> locs = buildClusters(3, 8);
        VehicleRoutePlan solution = solve(problem(locs, 3, false), EnvironmentMode.PHASE_ASSERT, 3);

        assertEquals(0, solution.getScore().hardScore());
        long used = solution.getVehicles().stream().filter(v -> !v.getVisits().isEmpty()).count();
        assertEquals(1, used, "sans equilibrage, un seul vehicule est utilise");
    }

    @Test
    void avecEquilibrageChaqueVehiculePrendUnClusterFullAssert() {
        // FULL_ASSERT verifie la coherence du calcul incremental de la contrainte au carre
        // (getTourDurationSeconds simule la tournee sans lire les variables fantomes).
        List<Location> locs = buildClusters(3, 3);
        VehicleRoutePlan solution = solve(problem(locs, 3, true), EnvironmentMode.FULL_ASSERT, 3);

        assertEquals(0, solution.getScore().hardScore());
        for (Vehicle v : solution.getVehicles()) {
            assertEquals(3, v.getVisits().size(), v.getId() + " doit avoir 3 visites");
            assertEquals(1, clustersOf(v).size(), v.getId() + " doit couvrir un seul cluster");
        }
    }

    @Test
    void avecEquilibrageVingtQuatrePointsTroisTournees() {
        List<Location> locs = buildClusters(3, 8);
        VehicleRoutePlan solution = solve(problem(locs, 3, true), EnvironmentMode.PHASE_ASSERT, 3);

        assertEquals(0, solution.getScore().hardScore());
        Set<String> clustersSeen = new HashSet<>();
        for (Vehicle v : solution.getVehicles()) {
            assertEquals(8, v.getVisits().size(), v.getId() + " doit avoir 8 visites");
            Set<String> clusters = clustersOf(v);
            assertEquals(1, clusters.size(), v.getId() + " doit couvrir un seul cluster");
            clustersSeen.addAll(clusters);
        }
        assertEquals(3, clustersSeen.size(), "les 3 clusters sont couverts");
    }

    @Test
    void laDureeSimuleeEstCoherenteAvecLesVariablesFantomes() {
        // Une visite ouvrant tard force une attente : la duree simulee (utilisee par la contrainte) doit etre
        // egale a l'heure de retour deduite des variables fantomes (arrivalTimeSeconds en cascade).
        List<Location> locs = buildClusters(2, 3);
        List<Vehicle> vehicles = List.of(
                new Vehicle("vehicle-0", UNLIMITED, locs.get(0), 0L, true),
                new Vehicle("vehicle-1", UNLIMITED, locs.get(0), 0L, true));
        List<Visit> visits = new ArrayList<>();
        for (int i = 1; i < locs.size(); i++) {
            Long minStart = i == 1 ? 2500L : null;
            visits.add(new Visit(locs.get(i).getId(), null, locs.get(i), 0, 120, minStart, null, null));
        }
        VehicleRoutePlan solution = solve(new VehicleRoutePlan(vehicles, visits), EnvironmentMode.FULL_ASSERT, 2);

        for (Vehicle v : solution.getVehicles()) {
            assertEquals(v.getReturnTimeSeconds() - v.getDepartureTimeSeconds(), v.getTourDurationSeconds(),
                    v.getId() + " : duree simulee = retour - depart");
        }
        Visit late = solution.getVisits().stream().filter(vi -> vi.getId().equals("c0-0")).findFirst().orElseThrow();
        assertTrue(late.getWaitingSeconds() > 0 || late.getArrivalTimeSeconds() >= 2500L,
                "la visite tardive attend ou arrive apres l'ouverture");
    }

    @Test
    void laSurchargeDuTempsDeResolutionParJobEstAppliquee() throws Exception {
        // Config globale : 1 s. Surcharge par job : 3 s. C'est le mecanisme utilise par /dispatch
        // (maxSolvingSeconds) sans toucher a la configuration partagee avec /optimize.
        SolverFactory<VehicleRoutePlan> factory = SolverFactory.create(config(EnvironmentMode.PHASE_ASSERT, 1));
        SolverManager<VehicleRoutePlan, UUID> manager = SolverManager.create(factory);
        try {
            List<Location> locs = buildClusters(3, 8);

            long t0 = System.currentTimeMillis();
            manager.solveBuilder().withProblemId(UUID.randomUUID()).withProblem(problem(locs, 3, true))
                    .run().getFinalBestSolution();
            long defaultMs = System.currentTimeMillis() - t0;

            long t1 = System.currentTimeMillis();
            manager.solveBuilder().withProblemId(UUID.randomUUID()).withProblem(problem(locs, 3, true))
                    .withConfigOverride(new SolverConfigOverride<VehicleRoutePlan>()
                            .withTerminationSpentLimit(Duration.ofSeconds(3)))
                    .run().getFinalBestSolution();
            long overriddenMs = System.currentTimeMillis() - t1;

            assertTrue(defaultMs < 2500, "sans surcharge : ~1 s (mesure " + defaultMs + " ms)");
            assertTrue(overriddenMs >= 2800, "avec surcharge : ~3 s (mesure " + overriddenMs + " ms)");
        } finally {
            manager.close();
        }
    }
}
