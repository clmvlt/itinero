package bzh.stackbzh.org.optimization.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import bzh.stackbzh.org.optimization.domain.Vehicle;
import bzh.stackbzh.org.optimization.domain.Visit;

/**
 * Contraintes du VRP :
 * <ul>
 *   <li><b>Dur</b> : capacite du vehicule depassee ; arrivee apres la fin de la fenetre horaire (retard) ;
 *   attente devant une fenetre au-dela du maximum tolere ({@code Visit.maxWaitingSeconds}) ; les deux
 *   extremites d'une mission appairee sur des vehicules differents ; enlevement place avant son
 *   chargement.</li>
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
 *
 * <p><b>Echelle des penalites dures.</b> Le score n'a qu'UN SEUL niveau dur, partage avec
 * {@code arrivalAfterTimeWindowEnd} dont la penalite se compte en SECONDES de retard (couramment des
 * milliers). Une penalite d'appairage valant 1 ou 2 serait donc negligeable : le solveur casserait
 * rationnellement une mission pour eviter dix minutes de retard — score "correct", resultat metier faux.
 * Les penalites d'appairage sont pour cette raison exprimees dans la MEME unite (des secondes), avec des
 * ordres de grandeur qui dominent tout retard realiste (cf. constantes ci-dessous).
 */
public class VehicleRoutingConstraintProvider implements ConstraintProvider {

    /**
     * Separer les deux extremites d'une mission coute l'equivalent de deux jours de retard. Strictement
     * superieur a la pire inversion atteignable ({@link #PRECEDENCE_BASE_SECONDS} + n x
     * {@link #PRECEDENCE_PER_POSITION_SECONDS}, soit ~98 000 s a 200 arrets) : sans cette marge, le solveur
     * pourrait trouver moins couteux de casser une mission que de reparer un ordre inverse.
     */
    private static final long SPLIT_PENALTY_SECONDS = 172_800;
    /** Toute inversion chargement/enlevement coute au moins une journee de retard : jamais negociable. */
    private static final long PRECEDENCE_BASE_SECONDS = 86_400;
    /**
     * ... plus une minute par position d'ecart. Cette part GRADUEE donne au solveur un gradient de
     * reparation : chaque echange qui rapproche les deux extremites fait baisser le score. Une penalite
     * plate creerait un plateau ou tous les ordres inverses se valent.
     */
    private static final long PRECEDENCE_PER_POSITION_SECONDS = 60;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[]{
                vehicleCapacity(factory),
                arrivalAfterTimeWindowEnd(factory),
                waitingExceedsMax(factory),
                shipmentPairSplit(factory),
                shipmentPrecedence(factory),
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

    /**
     * Les deux extremites d'une mission doivent etre servies par le MEME vehicule : on ne peut pas enlever
     * une marchandise chargee dans un autre camion.
     *
     * <p>Formulation en jointure (et non en parcours de {@code Vehicle.getVisits()}) pour deux raisons :
     * les deux extremites sont dans le meme tuple, donc Timefold reevalue la contrainte des que l'UNE OU
     * L'AUTRE bouge (correction du calcul incremental garantie par construction) ; et aucun tuple n'est
     * produit quand la requete ne contient aucune mission, donc le cout est strictement nul pour les
     * tournees classiques.
     */
    Constraint shipmentPairSplit(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(Visit::isPickup)
                .join(factory.forEach(Visit.class).filter(Visit::isDelivery),
                        Joiners.equal(Visit::getShipmentId))
                .filter((pickup, delivery) -> pickup.getVehicle() != delivery.getVehicle())
                .penalizeLong(HardSoftLongScore.ONE_HARD, (pickup, delivery) -> SPLIT_PENALTY_SECONDS)
                .asConstraint("Chargement et enlevement sur des vehicules differents");
    }

    /**
     * Dans une meme tournee, le chargement doit preceder l'enlevement. La comparaison porte sur les
     * {@code indexInRoute} (variables fantomes maintenues par Timefold) : c'est la seule facon sure de
     * comparer deux positions, cf. le javadoc de {@code Visit.indexInRoute}.
     */
    Constraint shipmentPrecedence(ConstraintFactory factory) {
        return factory.forEach(Visit.class)
                .filter(Visit::isPickup)
                .join(factory.forEach(Visit.class).filter(Visit::isDelivery),
                        Joiners.equal(Visit::getShipmentId))
                .filter((pickup, delivery) -> pickup.getVehicle() == delivery.getVehicle()
                        && pickup.getIndexInRoute() != null && delivery.getIndexInRoute() != null
                        && pickup.getIndexInRoute() > delivery.getIndexInRoute())
                .penalizeLong(HardSoftLongScore.ONE_HARD, (pickup, delivery) -> PRECEDENCE_BASE_SECONDS
                        + (pickup.getIndexInRoute() - delivery.getIndexInRoute())
                        * PRECEDENCE_PER_POSITION_SECONDS)
                .asConstraint("Enlevement avant le chargement");
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
