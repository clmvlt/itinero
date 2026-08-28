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

    @PlanningListVariable
    private List<Visit> visits = new ArrayList<>();

    public Vehicle() {
    }

    public Vehicle(String id, int capacity, Location homeLocation) {
        this(id, capacity, homeLocation, 0L);
    }

    public Vehicle(String id, int capacity, Location homeLocation, long departureTimeSeconds) {
        this.id = id;
        this.capacity = capacity;
        this.homeLocation = homeLocation;
        this.departureTimeSeconds = departureTimeSeconds;
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
