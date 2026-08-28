package bzh.stackbzh.org.optimization;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import bzh.stackbzh.org.optimization.domain.Location;
import bzh.stackbzh.org.optimization.domain.Vehicle;
import bzh.stackbzh.org.optimization.domain.VehicleRoutePlan;
import bzh.stackbzh.org.optimization.domain.Visit;
import bzh.stackbzh.org.optimization.solver.VehicleRoutingConstraintProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valide le modele Timefold (variables fantomes en cascade + contraintes de fenetres horaires) sur une
 * matrice synthetique, sans Spring ni GraphHopper. FULL_ASSERT verifie la coherence des variables fantomes.
 */
class TimeWindowSolverTest {

    private static final int UNLIMITED = Integer.MAX_VALUE / 2;

    /** Depot D et trois points A, B, C alignes : D-A 600 s, A-B 300 s, B-C 300 s, D-B 600, D-C 600, A-C 600. */
    private static Location[] buildLocations() {
        Location d = new Location("depot", 0, 0);
        Location a = new Location("loc-A", 0, 1);
        Location b = new Location("loc-B", 0, 2);
        Location c = new Location("loc-C", 0, 3);
        d.setDrivingTimeSeconds(Map.of("depot", 0L, "loc-A", 600L, "loc-B", 600L, "loc-C", 600L));
        a.setDrivingTimeSeconds(Map.of("depot", 600L, "loc-A", 0L, "loc-B", 300L, "loc-C", 600L));
        b.setDrivingTimeSeconds(Map.of("depot", 600L, "loc-A", 300L, "loc-B", 0L, "loc-C", 300L));
        c.setDrivingTimeSeconds(Map.of("depot", 600L, "loc-A", 600L, "loc-B", 300L, "loc-C", 0L));
        return new Location[]{d, a, b, c};
    }

    private static VehicleRoutePlan solve(VehicleRoutePlan problem) {
        SolverConfig config = new SolverConfig()
                .withSolutionClass(VehicleRoutePlan.class)
                .withEntityClasses(Vehicle.class, Visit.class)
                .withConstraintProviderClass(VehicleRoutingConstraintProvider.class)
                .withEnvironmentMode(EnvironmentMode.FULL_ASSERT)
                .withTerminationSpentLimit(Duration.ofSeconds(2));
        Solver<VehicleRoutePlan> solver = SolverFactory.<VehicleRoutePlan>create(config).buildSolver();
        return solver.solve(problem);
    }

    @Test
    void sansFenetreLeSolveurMinimiseLaConduite() {
        Location[] l = buildLocations();
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, l[0], 0L);
        List<Visit> visits = List.of(
                new Visit("A", "A", l[1], 0, 0),
                new Visit("B", "B", l[2], 0, 0),
                new Visit("C", "C", l[3], 0, 0));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), visits));

        assertEquals(0, solution.getScore().hardScore());
        assertEquals(-1800, solution.getScore().softScore(), "D-A-B-C-D (ou inverse) = 1800 s");
        Vehicle v = solution.getVehicles().get(0);
        assertEquals(3, v.getVisits().size());
        for (Visit visit : v.getVisits()) {
            assertNotNull(visit.getArrivalTimeSeconds(), "arrivalTime doit etre calcule pour chaque visite");
            assertEquals(0, visit.getWaitingSeconds());
            assertEquals(0, visit.getLatenessSeconds());
        }
    }

    @Test
    void laFenetreHoraireForceLOrdreDePassage() {
        Location[] l = buildLocations();
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, l[0], 0L);
        // A n'est atteignable dans [1200, 1300] qu'en 3e position : D->C (600) ->B (900) ->A (1200).
        List<Visit> visits = List.of(
                new Visit("A", "A", l[1], 0, 0, 1200L, 1300L),
                new Visit("B", "B", l[2], 0, 0),
                new Visit("C", "C", l[3], 0, 0));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), visits));

        assertEquals(0, solution.getScore().hardScore(), "fenetre respectable -> aucune violation dure");
        Vehicle v = solution.getVehicles().get(0);
        assertEquals(List.of("C", "B", "A"), v.getVisits().stream().map(Visit::getId).toList());
        Visit a = v.getVisits().get(2);
        assertEquals(1200L, a.getArrivalTimeSeconds());
        assertEquals(0, a.getWaitingSeconds());
        assertEquals(0, a.getLatenessSeconds());
        assertEquals(1800L, v.getReturnTimeSeconds());
    }

    @Test
    void arriveeEnAvanceProvoqueUneAttenteRepercuteeSurLesSuivants() {
        Location[] l = buildLocations();
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, l[0], 0L);
        // Ouverture de A a 700 s : quel que soit l'ordre, on attend (A en 1er : arrivee 600, attente 100).
        List<Visit> visits = List.of(
                new Visit("A", "A", l[1], 0, 60, 700L, 800L),
                new Visit("B", "B", l[2], 0, 0),
                new Visit("C", "C", l[3], 0, 0));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), visits));

        assertEquals(0, solution.getScore().hardScore());
        Vehicle v = solution.getVehicles().get(0);
        assertEquals(List.of("A", "B", "C"), v.getVisits().stream().map(Visit::getId).toList());
        Visit a = v.getVisits().get(0);
        assertEquals(600L, a.getArrivalTimeSeconds());
        assertEquals(100L, a.getWaitingSeconds());
        assertEquals(700L, a.getStartServiceTimeSeconds());
        assertEquals(760L, a.getDepartureTimeSeconds(), "debut de service + 60 s de service");
        Visit b = v.getVisits().get(1);
        assertEquals(1060L, b.getArrivalTimeSeconds(), "l'attente et le service a A decalent B");
        assertEquals(-(1800 + 100), solution.getScore().softScore(), "soft = conduite + attente");
    }

    @Test
    void fenetreImpossibleRenvoieUnScoreDurNegatifEtUnRetard() {
        Location[] l = buildLocations();
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, l[0], 0L);
        // A doit etre atteint avant 100 s : impossible (600 s minimum). Retard attendu = 500 s.
        List<Visit> visits = List.of(
                new Visit("A", "A", l[1], 0, 0, null, 100L),
                new Visit("B", "B", l[2], 0, 0));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), visits));

        assertTrue(solution.getScore().hardScore() < 0);
        Visit a = solution.getVehicles().get(0).getVisits().stream()
                .filter(vi -> vi.getId().equals("A")).findFirst().orElseThrow();
        assertEquals(600L, a.getArrivalTimeSeconds(), "le solveur place A en premier pour limiter le retard");
        assertEquals(500L, a.getLatenessSeconds());
        assertEquals(-500, solution.getScore().hardScore());
    }

    @Test
    void sansLimiteDAttenteUneLongueAttenteResteFaisable() {
        Location[] l = buildLocations();
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, l[0], 0L);
        // A n'ouvre qu'a 3000 s : au mieux on y arrive a 1500 s (D-B-C-A) -> 1500 s d'attente, tolerees.
        List<Visit> visits = List.of(
                new Visit("A", "A", l[1], 0, 0, 3000L, null, null),
                new Visit("B", "B", l[2], 0, 0),
                new Visit("C", "C", l[3], 0, 0));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), visits));

        assertEquals(0, solution.getScore().hardScore(), "sans limite, l'attente n'est jamais une violation");
        Visit a = solution.getVehicles().get(0).getVisits().stream()
                .filter(vi -> vi.getId().equals("A")).findFirst().orElseThrow();
        assertTrue(a.getWaitingSeconds() > 0);
        assertEquals(0, a.getExcessiveWaitingSeconds());
    }

    @Test
    void attenteAuDelaDuMaximumEstUneViolationDureMinimisee() {
        Location[] l = buildLocations();
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, l[0], 0L);
        // A n'ouvre qu'a 3000 s, attente max 900 s. Arrivee la plus tardive possible a A : D-B (600) -C (900)
        // -A (1500) -> attente 1500, dont 600 au-dela de la limite. Tout autre ordre attend davantage.
        List<Visit> visits = List.of(
                new Visit("A", "A", l[1], 0, 0, 3000L, null, 900L),
                new Visit("B", "B", l[2], 0, 0, null, null, 900L),
                new Visit("C", "C", l[3], 0, 0, null, null, 900L));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), visits));

        assertEquals(-600, solution.getScore().hardScore(), "le solveur retarde A au maximum (B-C-A)");
        Vehicle v = solution.getVehicles().get(0);
        assertEquals(List.of("B", "C", "A"), v.getVisits().stream().map(Visit::getId).toList());
        Visit a = v.getVisits().get(2);
        assertEquals(1500L, a.getArrivalTimeSeconds());
        assertEquals(1500L, a.getWaitingSeconds());
        assertEquals(600L, a.getExcessiveWaitingSeconds());
        assertTrue(a.isWaitingTooLong());
        assertEquals(3000L, a.getStartServiceTimeSeconds(), "les heures restent calculees avec l'attente");
    }

    @Test
    void laLimiteDAttenteEstRespecteeQuandUnOrdreLePermet() {
        Location[] l = buildLocations();
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, l[0], 0L);
        // A ouvre a 1400 s, attente max 300 s : A en 1er (arrivee 600) attendrait 800 s -> interdit ;
        // D-B-C-A arrive a 1500 -> aucune attente.
        List<Visit> visits = List.of(
                new Visit("A", "A", l[1], 0, 0, 1400L, null, 300L),
                new Visit("B", "B", l[2], 0, 0, null, null, 300L),
                new Visit("C", "C", l[3], 0, 0, null, null, 300L));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), visits));

        assertEquals(0, solution.getScore().hardScore());
        Visit a = solution.getVehicles().get(0).getVisits().stream()
                .filter(vi -> vi.getId().equals("A")).findFirst().orElseThrow();
        assertTrue(a.getWaitingSeconds() <= 300);
        assertEquals(0, a.getExcessiveWaitingSeconds());
    }
}
