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
 *   <li><b>Dur</b> : capacite du vehicule depassee ; arrivee apres la fin de la fenetre horaire (retard) ;
 *   attente devant une fenetre au-dela du maximum tolere ({@code Visit.maxWaitingSeconds}).</li>
 *   <li><b>Souple</b> : temps de conduite total ; temps d'attente devant une fenetre non encore ouverte ;
 *   et, pour les vehicules marques {@code balanceDuration} (mode {@code /dispatch}), le CARRE de la duree de
 *   chaque tournee.</li>
 * </ul>
 *
 * <p><b>Pourquoi le carre ?</b> Somme des carres = (total)^2 / K + K x variance des durees. Minimiser ce seul
 * terme reduit donc a la fois le temps global ET l'ecart entre vehicules, sans poids a regler : le solveur
 * utilise les K vehicules et forme des zones geographiques coherentes. Sans ce terme (mode {@code /optimize}),
 * minimiser le temps total conduit mathematiquement a tout mettre sur un seul vehicule (chaque vehicule
 * supplementaire ajoute un aller-retour au depot). Pour K = 1 les deux objectifs ont le meme optimum.
 */
public class VehicleRoutingConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                vehicleCapacity(factory),
                arrivalAfterTimeWindowEnd(factory),
                waitingExceedsMax(factory),
                minimizeDrivingTime(factory),
                minimizeWaitingTime(factory),
                balanceTourDurations(factory)
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

    Constraint waitingExceedsMax(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(visit -> visit.getVehicle() != null && visit.isWaitingTooLong())
                .penalizeLong(HardSoftLongScore.ONE_HARD, Visit::getExcessiveWaitingSeconds)
                .asConstraint("Attente trop longue avant l'ouverture de la fenetre horaire");
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

    /**
     * Mode {@code /dispatch} uniquement (vehicules {@code balanceDuration = true}) : penalite = duree de tournee
     * au carre (s^2). Domine numeriquement les termes lineaires ci-dessus, qui ne servent plus que de
     * departage. Une tournee de 24 h = 7,5e9 : pas de risque de depassement du {@code long}.
     */
    Constraint balanceTourDurations(ConstraintFactory factory) {
        return factory.forEach(Vehicle.class)
                .filter(Vehicle::isBalanceDuration)
                .penalizeLong(HardSoftLongScore.ONE_SOFT, vehicle -> {
                    long duration = vehicle.getTourDurationSeconds();
                    return duration * duration;
                })
                .asConstraint("Equilibrer les durees de tournee");
    }
}
