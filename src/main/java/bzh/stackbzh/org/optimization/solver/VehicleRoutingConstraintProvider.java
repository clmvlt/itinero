package bzh.stackbzh.org.optimization.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import bzh.stackbzh.org.optimization.domain.Vehicle;
import bzh.stackbzh.org.optimization.domain.Visit;

/**
 * Contraintes du VRP :
 * <ul>
 *   <li><b>Dur</b> : capacite du vehicule depassee ; arrivee apres la fin de la fenetre horaire (retard).</li>
 *   <li><b>Souple</b> : temps de conduite total ; temps d'attente devant une fenetre non encore ouverte.</li>
 * </ul>
 */
public class VehicleRoutingConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                vehicleCapacity(factory),
                arrivalAfterTimeWindowEnd(factory),
                minimizeDrivingTime(factory),
                minimizeWaitingTime(factory)
        };
    }

    Constraint vehicleCapacity(ConstraintFactory factory) {
        return factory.forEach(Vehicle.class)
                .filter(vehicle -> vehicle.getTotalDemand() > vehicle.getCapacity())
                .penalizeLong(HardSoftLongScore.ONE_HARD,
                        vehicle -> (long) (vehicle.getTotalDemand() - vehicle.getCapacity()))
                .asConstraint("Capacite vehicule depassee");
    }

    Constraint arrivalAfterTimeWindowEnd(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVehicle() != null && visit.isLate())
                .penalizeLong(HardSoftLongScore.ONE_HARD, Visit::getLatenessSeconds)
                .asConstraint("Arrivee apres la fin de la fenetre horaire");
    }

    Constraint minimizeDrivingTime(ConstraintFactory factory) {
        return factory.forEach(Vehicle.class)
                .penalizeLong(HardSoftLongScore.ONE_SOFT,
                        Vehicle::getTotalDrivingTimeSeconds)
                .asConstraint("Minimiser le temps de conduite");
    }

    Constraint minimizeWaitingTime(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVehicle() != null && visit.getWaitingSeconds() > 0)
                .penalizeLong(HardSoftLongScore.ONE_SOFT, Visit::getWaitingSeconds)
                .asConstraint("Minimiser le temps d'attente aux fenetres horaires");
    }
}
