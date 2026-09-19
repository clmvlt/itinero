package bzh.stackbzh.org.optimization;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import bzh.stackbzh.org.optimization.domain.Location;
import bzh.stackbzh.org.optimization.domain.StopType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valide les MISSIONS APPAIREES (chargement -> enlevement) sur une matrice synthetique, sans Spring ni
 * GraphHopper : memes vehicule et ordre imposes, echelle des penalites, et coherence du calcul incremental.
 *
 * <p>Plusieurs cas utilisent une matrice <b>volontairement asymetrique</b> (parfaitement realiste : sens
 * interdits, bretelles). Sans asymetrie, une tournee a deux arrets a exactement le meme cout dans les deux
 * sens et le test ne prouverait rien.
 */
class ShipmentSolverTest {

    private static final int UNLIMITED = Integer.MAX_VALUE / 2;

    /**
     * Depot + un point de chargement P + un point d'enlevement L. Aller au chargement coute cher (900 s) mais
     * en revenir est court (500 s). Consequence : l'ordre le plus RAPIDE est depot -> L -> P -> depot
     * (100 + 100 + 500 = 700 s), c'est-a-dire l'enlevement AVANT le chargement. L'ordre correct
     * depot -> P -> L -> depot coute 900 + 100 + 100 = 1100 s.
     */
    private static Map<String, Location> asymmetricPair() {
        Location depot = new Location("depot", 0, 0);
        Location pickup = new Location("loc-P", 10, 0);
        Location delivery = new Location("loc-L", 1, 0);
        depot.setDrivingTimeSeconds(Map.of("depot", 0L, "loc-P", 900L, "loc-L", 100L));
        pickup.setDrivingTimeSeconds(Map.of("depot", 500L, "loc-P", 0L, "loc-L", 100L));
        delivery.setDrivingTimeSeconds(Map.of("depot", 100L, "loc-P", 100L, "loc-L", 0L));
        return Map.of("depot", depot, "P", pickup, "L", delivery);
    }

    /** Cree les deux extremites d'une mission, cablees l'une a l'autre comme le fait OptimizationService. */
    private static Visit[] shipment(String shipmentId, Location pickupLoc, Location deliveryLoc,
                                    Long deliveryWindowEnd) {
        Visit pickup = new Visit(shipmentId + "-pickup", null, pickupLoc, 0, 0);
        Visit delivery = new Visit(shipmentId + "-delivery", null, deliveryLoc, 0, 0, null, deliveryWindowEnd);
        pickup.setShipment(shipmentId, StopType.PICKUP);
        delivery.setShipment(shipmentId, StopType.DELIVERY);
        pickup.setPairedVisit(delivery);
        delivery.setPairedVisit(pickup);
        return new Visit[]{pickup, delivery};
    }

    private static VehicleRoutePlan solve(VehicleRoutePlan problem, EnvironmentMode mode, int seconds) {
        SolverConfig config = new SolverConfig()
                .withSolutionClass(VehicleRoutePlan.class)
                .withEntityClasses(Vehicle.class, Visit.class)
                .withConstraintProviderClass(VehicleRoutingConstraintProvider.class)
                .withEnvironmentMode(mode)
                .withTerminationSpentLimit(Duration.ofSeconds(seconds));
        Solver<VehicleRoutePlan> solver = SolverFactory.<VehicleRoutePlan>create(config).buildSolver();
        return solver.solve(problem);
    }

    private static List<String> idsOf(Vehicle vehicle) {
        return vehicle.getVisits().stream().map(Visit::getId).toList();
    }

    /**
     * Retrouve une visite DANS LA SOLUTION renvoyee. Indispensable : Timefold clone en profondeur le probleme,
     * donc les objets passes en entree gardent des variables fantomes nulles — les interroger ne prouverait
     * rien (assertSame(null, null) passe).
     */
    private static Visit visitOf(VehicleRoutePlan solution, String id) {
        return solution.getVisits().stream()
                .filter(v -> v.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("visite absente de la solution : " + id));
    }

    /** Aucune mission cassee : en parcourant chaque tournee, tout enlevement suit son chargement. */
    private static void assertPairsIntact(VehicleRoutePlan solution) {
        for (Vehicle vehicle : solution.getVehicles()) {
            Set<String> loaded = new HashSet<>();
            for (Visit visit : vehicle.getVisits()) {
                if (visit.isPickup()) {
                    loaded.add(visit.getShipmentId());
                } else if (visit.isDelivery() && visit.isPaired()) {
                    assertTrue(loaded.contains(visit.getShipmentId()),
                            "mission " + visit.getShipmentId() + " : enlevement avant son chargement, "
                                    + "ou extremites sur deux vehicules");
                }
            }
        }
    }

    @Test
    void leChargementPasseAvantLEnlevement() {
        Map<String, Location> locs = asymmetricPair();
        Visit[] m = shipment("M1", locs.get("P"), locs.get("L"), null);
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, locs.get("depot"), 0L);

        // Volontairement fournis dans le DESORDRE : l'enlevement d'abord.
        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), List.of(m[1], m[0])),
                EnvironmentMode.FULL_ASSERT, 2);

        assertEquals(0, solution.getScore().hardScore(), "l'ordre correct est atteignable ici");
        assertEquals(List.of("M1-pickup", "M1-delivery"), idsOf(solution.getVehicles().get(0)));
        assertEquals(-1100, solution.getScore().softScore(),
                "le solveur accepte 1100 s (ordre correct) plutot que 700 s (enlevement d'abord)");
    }

    @Test
    void lesDeuxExtremitesFinissentSurLeMemeVehicule() {
        // balanceDuration = true recompense l'EQUILIBRE : separer la mission (un arret par vehicule) donnerait
        // une somme des carres bien plus faible que de tout mettre sur un seul. La contrainte dure doit primer.
        Map<String, Location> locs = asymmetricPair();
        Visit[] m = shipment("M1", locs.get("P"), locs.get("L"), null);
        List<Vehicle> vehicles = List.of(
                new Vehicle("vehicle-0", UNLIMITED, locs.get("depot"), 0L, true),
                new Vehicle("vehicle-1", UNLIMITED, locs.get("depot"), 0L, true));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(vehicles, List.of(m[0], m[1])),
                EnvironmentMode.FULL_ASSERT, 2);

        assertEquals(0, solution.getScore().hardScore(), "aucune mission ne doit etre cassee");
        assertPairsIntact(solution);
        Visit pickup = visitOf(solution, "M1-pickup");
        Visit delivery = visitOf(solution, "M1-delivery");
        assertSame(pickup.getVehicle(), delivery.getVehicle(), "les deux extremites sur le meme vehicule");
        assertTrue(pickup.getIndexInRoute() < delivery.getIndexInRoute(), "chargement avant enlevement");
    }

    /**
     * LE test de non-regression du modele : {@link EnvironmentMode#FULL_ASSERT} recalcule le score a partir de
     * zero apres chaque mouvement et le compare au calcul incremental. Il faut au moins 2 vehicules et
     * plusieurs missions pour que les mouvements INTER-vehicules (ceux qui cassent et recollent les paires)
     * soient reellement explores.
     */
    @Test
    void laCoherenceIncrementaleEstVerifieeEnFullAssert() {
        List<Location> locs = buildClusters(3);
        List<Vehicle> vehicles = List.of(
                new Vehicle("vehicle-0", UNLIMITED, locs.get(0), 0L, true),
                new Vehicle("vehicle-1", UNLIMITED, locs.get(0), 0L, true));

        List<Visit> visits = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            Visit[] m = shipment("M" + c, locs.get(1 + 2 * c), locs.get(2 + 2 * c), null);
            visits.add(m[1]);
            visits.add(m[0]);
        }

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(vehicles, visits), EnvironmentMode.FULL_ASSERT, 3);

        assertEquals(0, solution.getScore().hardScore(), "les 3 missions sont toutes tenables");
        assertPairsIntact(solution);
    }

    @Test
    void casserUnePaireCouteToujoursPlusCherQuUnRetard() {
        // L'enlevement doit etre servi avant 500 s. En respectant la mission (depot -> P -> L), on y arrive a
        // 1000 s : 500 s de retard. En la CASSANT, un second vehicule irait directement a L (arrivee 100 s) et
        // il n'y aurait aucun retard. Les penalites sont calibrees pour que le solveur refuse ce marche.
        Map<String, Location> locs = asymmetricPair();
        Visit[] m = shipment("M1", locs.get("P"), locs.get("L"), 500L);
        List<Vehicle> vehicles = List.of(
                new Vehicle("vehicle-0", UNLIMITED, locs.get("depot"), 0L),
                new Vehicle("vehicle-1", UNLIMITED, locs.get("depot"), 0L));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(vehicles, List.of(m[0], m[1])),
                EnvironmentMode.FULL_ASSERT, 2);

        assertPairsIntact(solution);
        assertSame(visitOf(solution, "M1-pickup").getVehicle(), visitOf(solution, "M1-delivery").getVehicle(),
                "la mission reste entiere malgre le retard");
        assertEquals(-500, solution.getScore().hardScore(),
                "seul le retard (500 s) est penalise : ni separation (172 800) ni inversion (86 400+)");
    }

    @Test
    void uneMissionAUneSeuleExtremiteNImposeAucunOrdre() {
        // Mission "chargee au depot" : une seule extremite, donc pas de jumelle -> aucune precedence.
        Map<String, Location> locs = asymmetricPair();
        Visit delivery = new Visit("M1-delivery", null, locs.get("L"), 0, 0);
        delivery.setShipment("M1", StopType.DELIVERY);
        Visit other = new Visit("X", null, locs.get("P"), 0, 0);
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, locs.get("depot"), 0L);

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), List.of(delivery, other)),
                EnvironmentMode.FULL_ASSERT, 2);

        assertEquals(0, solution.getScore().hardScore());
        assertEquals(List.of("M1-delivery", "X"), idsOf(solution.getVehicles().get(0)),
                "le solveur reste libre de servir l'enlevement en premier");
        assertEquals(-700, solution.getScore().softScore(), "l'ordre le plus rapide est conserve");
    }

    @Test
    void sansMissionLeComportementEstInchange() {
        // Deux points ordinaires : les contraintes d'appairage ne produisent aucun tuple et ne coutent rien.
        Map<String, Location> locs = asymmetricPair();
        Vehicle vehicle = new Vehicle("vehicle-0", UNLIMITED, locs.get("depot"), 0L);
        List<Visit> visits = List.of(
                new Visit("P", null, locs.get("P"), 0, 0),
                new Visit("L", null, locs.get("L"), 0, 0));

        VehicleRoutePlan solution = solve(new VehicleRoutePlan(List.of(vehicle), visits),
                EnvironmentMode.FULL_ASSERT, 2);

        assertEquals(0, solution.getScore().hardScore());
        assertEquals(List.of("L", "P"), idsOf(solution.getVehicles().get(0)));
        assertEquals(-700, solution.getScore().softScore(), "le trajet le plus court, sans contrainte ajoutee");
    }

    /**
     * Depot en (0,0) et {@code clusters} groupes de 2 points (une mission par groupe), chaque groupe dans une
     * direction differente. Temps = distance euclidienne x 100, symetrique.
     */
    private static List<Location> buildClusters(int clusters) {
        List<Location> locs = new ArrayList<>();
        locs.add(new Location("depot", 0, 0));
        for (int c = 0; c < clusters; c++) {
            double angle = 2 * Math.PI * c / clusters;
            double cx = 10 * Math.cos(angle);
            double cy = 10 * Math.sin(angle);
            locs.add(new Location("c" + c + "-pickup", cx, cy));
            locs.add(new Location("c" + c + "-delivery", cx + 0.6, cy + 0.6));
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
}
