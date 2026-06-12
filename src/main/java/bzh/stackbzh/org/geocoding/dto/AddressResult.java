package bzh.stackbzh.org.geocoding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Adresse trouvee, avec ses coordonnees et son score de pertinence.")
public record AddressResult(
        @Schema(description = "Libelle complet et lisible de l'adresse.", example = "12 Rue du Bocage, 35000 Rennes")
        String label,
        @Schema(description = "Numero de voie (peut etre vide).", example = "12")
        String houseNumber,
        @Schema(description = "Nom de la voie.", example = "Rue du Bocage")
        String street,
        @Schema(description = "Code postal.", example = "35000")
        String postcode,
        @Schema(description = "Commune.", example = "Rennes")
        String city,
        @Schema(description = "Latitude WGS84.", example = "48.1147")
        double lat,
        @Schema(description = "Longitude WGS84.", example = "-1.6794")
        double lon,
        @Schema(description = "Score de pertinence Lucene (plus eleve = plus pertinent).", example = "8.42")
        float score) {
}
