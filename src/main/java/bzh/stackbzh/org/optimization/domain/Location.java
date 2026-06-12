package bzh.stackbzh.org.optimization.domain;

import java.util.Map;
import java.util.Objects;

public class Location {

    private final String id;
    private final double lat;
    private final double lon;

    private Map<String, Long> drivingTimeSeconds;

    public Location(String id, double lat, double lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
    }

    public String getId() {
        return id;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public void setDrivingTimeSeconds(Map<String, Long> drivingTimeSeconds) {
        this.drivingTimeSeconds = drivingTimeSeconds;
    }

    public long getDrivingTimeTo(Location other) {
        if (drivingTimeSeconds == null) {
            throw new IllegalStateException("Matrice de temps non initialisee pour la location " + id);
        }
        Long time = drivingTimeSeconds.get(other.id);
        if (time == null) {
            throw new IllegalStateException("Temps de trajet manquant : " + id + " -> " + other.id);
        }
        return time;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Location other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
