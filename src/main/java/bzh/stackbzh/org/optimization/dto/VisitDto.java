package bzh.stackbzh.org.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Point a visiter dans une tournee.")
public record VisitDto(
        @Schema(description = "Identifiant du point (optionnel ; auto-genere si absent).", example = "A")
        String id,
        @Schema(description = "Libelle lisible (optionnel).", example = "Client A")
        String name,
        @Schema(description = "Latitude WGS84.", example = "47.2184", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Double lat,
        @Schema(description = "Longitude WGS84.", example = "-1.5536", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Double lon,
        @Schema(description = "Demande / charge consommee a ce point (optionnel ; defaut 0). Comparee a la capacite du vehicule.",
                example = "1")
        Integer demand,
        @Schema(description = "Duree d'arret / de service a ce point en secondes (optionnel ; defaut 0). "
                + "Decale les heures d'arrivee des points suivants.", example = "300")
        Integer serviceDurationSeconds) {

    public int resolvedDemand() {
        return demand != null ? demand : 0;
    }

    public int resolvedServiceDurationSeconds() {
        return serviceDurationSeconds != null ? Math.max(0, serviceDurationSeconds) : 0;
    }
}
