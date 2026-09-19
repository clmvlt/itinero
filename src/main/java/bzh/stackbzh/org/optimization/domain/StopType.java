package bzh.stackbzh.org.optimization.domain;

/**
 * Role d'un arret dans une mission appairee (shipment) :
 * <ul>
 *   <li>{@link #PICKUP} : chargement de la marchandise dans le vehicule ;</li>
 *   <li>{@link #DELIVERY} : enlevement / depose de cette marchandise.</li>
 * </ul>
 *
 * <p>Les deux extremites d'une mission doivent etre servies par le MEME vehicule, le chargement
 * AVANT l'enlevement (contraintes dures {@code Chargement et enlevement sur des vehicules differents}
 * et {@code Enlevement avant le chargement}). Un arret simple (non appaire) n'a pas de role : son
 * {@code stopType} vaut {@code null}.
 */
public enum StopType {
    PICKUP,
    DELIVERY
}
