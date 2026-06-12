package bzh.stackbzh.org.geocoding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Adresse trouvee, avec ses coordonnees et son score de pertinence.")
public record AddressResult(
        @Schema(description = "Libelle complet et lisible de l'adresse. En resultat de type `street`, "
                + "le numero est omis (ex : `Rue du Bocage, 35000 Rennes`).",
                example = "12 Rue du Bocage, 35000 Rennes")
        String label,
        @Schema(description = "Numero de voie. Vide pour un resultat de type `street` (agrege au niveau de la voie).",
                example = "12")
        String houseNumber,
        @Schema(description = "Nom de la voie.", example = "Rue du Bocage")
        String street,
        @Schema(description = "Code postal.", example = "35000")
        String postcode,
        @Schema(description = "Commune.", example = "Rennes")
        String city,
        @Schema(description = "Latitude WGS84. Pour un resultat `street`, coordonnee du point representatif "
                + "(le plus proche de la position fournie si `lat`/`lon` sont renseignes).", example = "48.1147")
        double lat,
        @Schema(description = "Longitude WGS84.", example = "-1.6794")
        double lon,
        @Schema(description = "Type de resultat : `street` (voie agregee, sans numero — renvoye quand la requete "
                + "ne contient aucun numero de voie) ou `housenumber` (adresse precise avec numero).",
                example = "street", allowableValues = {"street", "housenumber"})
        String type,
        @Schema(description = "Distance en metres entre la position fournie (`lat`/`lon`) et ce resultat. "
                + "`null` si aucune position n'a ete fournie dans la requete.",
                example = "742.0", nullable = true)
        Double distanceMeters,
        @Schema(description = "Score de pertinence (plus eleve = plus pertinent). Sans position : score textuel "
                + "Lucene. Avec position : score textuel pondere par la proximite (decroissance avec la distance).",
                example = "8.42")
        float score) {
}
