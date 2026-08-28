package bzh.stackbzh.org.optimization.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.CascadingUpdateShadowVariable;
import ai.timefold.solver.core.api.domain.variable.InverseRelationShadowVariable;
import ai.timefold.solver.core.api.domain.variable.NextElementShadowVariable;
import ai.timefold.solver.core.api.domain.variable.PreviousElementShadowVariable;

/**
 * Point a visiter. Entite de planification "fantome" : elle ne porte aucune variable genuine
 * (l'ordre de passage est la {@code @PlanningListVariable visits} de {@link Vehicle}), mais des
 * variables fantomes maintenues par Timefold : le vehicule auquel elle est affectee, la visite
 * precedente/suivante et l'heure d'arrivee (recalculee en cascade par Timefold via
 * {@link CascadingUpdateShadowVariable} -> {@link #updateArrivalTime()}).
 *
 * <p>Toutes les heures du domaine sont exprimees en <b>secondes ecoulees depuis l'heure de depart
 * de la tournee</b> ({@code OptimizeRequest.departureTime}), ce qui evite de manipuler des dates
 * dans le solveur. La fenetre horaire est optionnelle : {@code minStartSeconds} / {@code maxStartSeconds}
 * a {@code null} = pas de contrainte de ce cote.
 */
@PlanningEntity
public class Visit {

    @PlanningId
    private String id;
    private String name;
    private Location location;
    private int demand;
    private int serviceDurationSeconds;
    /** Debut de fenetre (offset en s depuis le depart) ; null = pas de borne basse. */
    private Long minStartSeconds;
    /** Fin de fenetre (offset en s depuis le depart) ; null = pas de borne haute. */
    private Long maxStartSeconds;

    @InverseRelationShadowVariable(sourceVariableName = "visits")
    private Vehicle vehicle;

    @PreviousElementShadowVariable(sourceVariableName = "visits")
    private Visit previousVisit;

    @NextElementShadowVariable(sourceVariableName = "visits")
    private Visit nextVisit;

    /** Heure d'arrivee (offset en s) ; null tant que la visite n'est affectee a aucun vehicule. */
    @CascadingUpdateShadowVariable(targetMethodName = "updateArrivalTime")
    private Long arrivalTimeSeconds;

    public Visit() {
    }

    public Visit(String id, String name, Location location, int demand, int serviceDurationSeconds) {
        this(id, name, location, demand, serviceDurationSeconds, null, null);
    }

    public Visit(String id, String name, Location location, int demand, int serviceDurationSeconds,
                 Long minStartSeconds, Long maxStartSeconds) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.demand = demand;
        this.serviceDurationSeconds = serviceDurationSeconds;
        this.minStartSeconds = minStartSeconds;
        this.maxStartSeconds = maxStartSeconds;
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

    public Long getMinStartSeconds() {
        return minStartSeconds;
    }

    public Long getMaxStartSeconds() {
        return maxStartSeconds;
    }

    public boolean hasTimeWindow() {
        return minStartSeconds != null || maxStartSeconds != null;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public void setVehicle(Vehicle vehicle) {
        this.vehicle = vehicle;
    }

    public Visit getPreviousVisit() {
        return previousVisit;
    }

    public void setPreviousVisit(Visit previousVisit) {
        this.previousVisit = previousVisit;
    }

    public Visit getNextVisit() {
        return nextVisit;
    }

    public void setNextVisit(Visit nextVisit) {
        this.nextVisit = nextVisit;
    }

    public Long getArrivalTimeSeconds() {
        return arrivalTimeSeconds;
    }

    public void setArrivalTimeSeconds(Long arrivalTimeSeconds) {
        this.arrivalTimeSeconds = arrivalTimeSeconds;
    }

    /**
     * Appele par Timefold des que {@code vehicle} ou {@code previousVisit} change ; la mise a jour
     * se propage automatiquement aux visites suivantes tant que la valeur change. L'heure d'arrivee
     * tient compte de l'attente eventuelle a la visite precedente (service demarre au plus tot a
     * {@code minStartSeconds}).
     */
    public void updateArrivalTime() {
        if (vehicle == null) {
            arrivalTimeSeconds = null;
            return;
        }
        Long previousDeparture = previousVisit == null
                ? vehicle.getDepartureTimeSeconds()
                : previousVisit.getDepartureTimeSeconds();
        arrivalTimeSeconds = previousDeparture == null
                ? null
                : previousDeparture + getDrivingTimeFromPreviousSeconds();
    }

    /** Debut effectif du service : arrivee, ou debut de fenetre si on arrive en avance (attente). */
    public Long getStartServiceTimeSeconds() {
        if (arrivalTimeSeconds == null) {
            return null;
        }
        return minStartSeconds != null ? Math.max(arrivalTimeSeconds, minStartSeconds) : arrivalTimeSeconds;
    }

    /** Heure de depart du point : debut du service + duree de service. */
    public Long getDepartureTimeSeconds() {
        Long start = getStartServiceTimeSeconds();
        return start == null ? null : start + serviceDurationSeconds;
    }

    /** Temps d'attente sur place avant l'ouverture de la fenetre (0 si pas de fenetre / pas en avance). */
    public long getWaitingSeconds() {
        if (arrivalTimeSeconds == null || minStartSeconds == null) {
            return 0;
        }
        return Math.max(0, minStartSeconds - arrivalTimeSeconds);
    }

    /** Retard par rapport a la fin de fenetre (0 si dans les temps / pas de fenetre). */
    public long getLatenessSeconds() {
        if (arrivalTimeSeconds == null || maxStartSeconds == null) {
            return 0;
        }
        return Math.max(0, arrivalTimeSeconds - maxStartSeconds);
    }

    public boolean isLate() {
        return getLatenessSeconds() > 0;
    }

    /** Temps de trajet depuis l'element precedent (depot ou visite precedente), en s. */
    public long getDrivingTimeFromPreviousSeconds() {
        if (vehicle == null) {
            return 0;
        }
        Location from = previousVisit == null ? vehicle.getHomeLocation() : previousVisit.getLocation();
        return from.getDrivingTimeTo(location);
    }

    @Override
    public String toString() {
        return name != null ? name : id;
    }
}
