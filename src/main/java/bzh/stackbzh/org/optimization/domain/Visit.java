package bzh.stackbzh.org.optimization.domain;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;

public class Visit {

    @PlanningId
    private String id;
    private String name;
    private Location location;
    private int demand;
    private int serviceDurationSeconds;

    public Visit() {
    }

    public Visit(String id, String name, Location location, int demand, int serviceDurationSeconds) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.demand = demand;
        this.serviceDurationSeconds = serviceDurationSeconds;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Location getLocation() {
        return location;
    }

    public int getDemand() {
        return demand;
    }

    public int getServiceDurationSeconds() {
        return serviceDurationSeconds;
    }

    @Override
    public String toString() {
        return name != null ? name : id;
    }
}
