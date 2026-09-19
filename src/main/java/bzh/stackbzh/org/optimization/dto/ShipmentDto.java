package bzh.stackbzh.org.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

@Schema(description = """
        MISSION APPAIREE : une marchandise chargee a un endroit (`pickup`) puis enlevee/deposee a un autre \
        (`delivery`). L'API garantit alors deux regles que `visits` seul ne permet pas d'exprimer :
        1. les DEUX arrets sont servis par le MEME vehicule ;
        2. le CHARGEMENT passe AVANT l'enlevement dans l'ordre de la tournee.

        Une mission peut n'avoir qu'UNE extremite : l'autre bout est alors le depot (`pickup` absent = \
        marchandise chargee au depot au depart, cas de la livraison classique ; `delivery` absent = \
        marchandise ramenee au depot en fin de tournee, cas de la collecte). Comme le depot encadre deja \
        la tournee, aucune contrainte de precedence n'est necessaire dans ce cas : l'arret se comporte \
        comme un point de `visits`.

        Les deux extremites sont des `VisitDto` ordinaires : coordonnees, duree de service et fenetre \
        horaire propres a CHAQUE extremite (on peut donc exiger un chargement le matin et une depose \
        l'apres-midi). ATTENTION au champ `demand` : il garde sa semantique habituelle (somme comparee a \
        `vehicleCapacity` sur l'ensemble de la tournee, PAS un suivi de la charge a bord). Le porter sur \
        le `pickup` et laisser `delivery.demand` a 0, sinon la charge est comptee deux fois.""")
public record ShipmentDto(
        @Schema(description = "Identifiant de la mission. Repris tel quel dans `stops[].shipmentId` de la "
                + "reponse, ce qui permet de recoller les deux arrets. Auto-genere (`s<index>`) si absent. "
                + "Doit etre unique dans la requete.",
                example = "M1", nullable = true)
        String id,

        @Schema(description = "Libelle de la mission, pour l'affichage. Sans effet sur l'optimisation.",
                example = "Palette Dupont", nullable = true)
        String name,

        // NB : en OpenAPI 3.0, un champ de type objet se reduit a un `$ref` seul et springdoc en retire la
        // description (comportement deja subi par `depot`, `balance`, `legFromPrevious`...). La semantique de
        // ces deux champs — et notamment "absent = le depot" — est donc portee par la description de la
        // CLASSE ci-dessus et par l'@Operation du controleur. Ne PAS tenter de la sauver avec `allOf` :
        // springdoc part en recursion infinie (StackOverflowError sur /v3/api-docs et Swagger UI).
        @Schema(description = "Arret de CHARGEMENT : ou la marchandise est prise en charge. Absent (null) = "
                + "chargee au depot au moment du depart (livraison classique). Point complet : il porte sa "
                + "propre duree de service et sa propre fenetre horaire.", nullable = true)
        @Valid VisitDto pickup,

        @Schema(description = "Arret d'ENLEVEMENT / de depose : ou la marchandise est livree. Absent (null) = "
                + "ramenee au depot en fin de tournee (collecte). Point complet : il porte sa propre duree de "
                + "service et sa propre fenetre horaire, distinctes de celles du chargement.", nullable = true)
        @Valid VisitDto delivery) {

    /** true si la mission impose reellement un ordre (ses deux extremites sont des arrets). */
    @Schema(hidden = true)
    public boolean isPaired() {
        return pickup != null && delivery != null;
    }

    @AssertTrue(message = "une mission doit avoir au moins un pickup ou un delivery")
    @Schema(hidden = true)
    public boolean isEndpointPresent() {
        return pickup != null || delivery != null;
    }
}
