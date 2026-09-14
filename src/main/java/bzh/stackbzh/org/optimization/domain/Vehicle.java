package bzh.stackbzh.org.optimization.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable;

import java.util.ArrayList;
import java.util.List;

@PlanningEntity
public class Vehicle {

    @PlanningId
    private String id;
    private int capacity;
    private Location homeLocation;
    /** Heure de depart du depot, en secondes depuis l'origine des temps de la tournee (0 = departureTime). */
    private long departureTimeSeconds;
    /**
     * true = la duree de cette tournee est penalisee au CARRE (contrainte souple {@code balanceTourDurations}) :
     * le solveur repartit alors les visites entre les vehicules en equilibrant les durees (mode
     * {@code /optimization/dispatch}). false = seul le temps total compte (mode {@code /optimize}, ou le
     * solveur tend a tout mettre sur un seul vehicule).
     */
    private boolean balanceDuration;

    @PlanningListVariable
    private List<Visit> visits = new ArrayList<>();

    public Vehicle() {
    }

    public Vehicle(String id, int capacity, Location homeLocation) {
        this(id, capacity, homeLocation, 0L);
    }

    public Vehicle(String id, int capacity, Location homeLocation, long departureTimeSeconds) {
        this(id, capacity, homeLocation, departureTimeSeconds, false);
    }

    public Vehicle(String id, int capacity, Location homeLocation, long departureTimeSeconds,
                   boolean balanceDuration) {
        this.id = id;
        this.capacity = capacity;
        this.homeLocation = homeLocation;
        this.departureTimeSeconds = departureTimeSeconds;
        this.balanceDuration = balanceDuration;
    }

    public String getId() {
        return id;
    }

    public int getCapacity() {
        return capacity;
    }

    public Location getHomeLocation() {
        return homeLocation;
    }

    public long getDepartureTimeSeconds() {
        return departureTimeSeconds;
    }

    public boolean isBalanceDuration() {
        return balanceDuration;
    }

    public List<Visit> getVisits() {
        return visits;
    }

    public void setVisits(List<Visit> visits) {
        this.visits = visits;
    }

    public int getTotalDemand() {
        int total = 0;
        for (Visit visit : visits) {
            total += visit.getDemand();
        }
        return total;
    }

    public long getTotalDrivingTimeSeconds() {
        if (visits.isEmpty()) {
            return 0;
        }
        long total = 0;
        Location previous = homeLocation;
        for (Visit visit : visits) {
            total += previous.getDrivingTimeTo(visit.getLocation());
            previous = visit.getLocation();
        }
        total += previous.getDrivingTimeTo(homeLocation);
        return total;
    }

    /**
     * Duree totale de la tournee (s) = conduite + service + attentes devant les fenetres horaires, du depart
     * du depot au retour. Recalculee par simulation a partir de la seule liste {@code visits} (sans lire les
     * variables fantomes des visites) pour que la contrainte souple {@code balanceTourDurations}, evaluee
     * sur le vehicule, reste coherente en calcul incremental. Meme regle que {@link Visit#updateArrivalTime()} :
     * arrivee = depart du precedent + trajet ; service au plus tot a l'ouverture de la fenetre. 0 si vide.
     */
    public long getTourDurationSeconds() {
        if (visits.isEmpty()) {
            return 0;
        }
        long clock = departureTimeSeconds;
        Location previous = homeLocation;
        for (Visit visit : visits) {
            clock += previous.getDrivingTimeTo(visit.getLocation());
            Long minStart = visit.getMinStartSeconds();
            if (minStart != null && clock < minStart) {
                clock = minStart;
            }
            clock += visit.getServiceDurationSeconds();
            previous = visit.getLocation();
        }
        clock += previous.getDrivingTimeTo(homeLocation);
        return clock - departureTimeSeconds;
    }

    /** Heure de retour au depot (offset en s), en tenant compte des attentes aux fenetres horaires. */
    public long getReturnTimeSeconds() {
        if (visits.isEmpty()) {
            return departureTimeSeconds;
        }
        Visit last = visits.get(visits.size() - 1);
        Long lastDeparture = last.getDepartureTimeSeconds();
        if (lastDeparture == null) {
            return departureTimeSeconds;
        }
        return lastDeparture + last.getLocation().getDrivingTimeTo(homeLocation);
    }

    @Override
    public String toString() {
        return id;
    }
}
