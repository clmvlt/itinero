package bzh.stackbzh.org.optimization.dto;

import bzh.stackbzh.org.routing.dto.Coordinate;
import bzh.stackbzh.org.routing.dto.GeometryFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Demande d'optimisation de tournee : depot commun, vehicules, points a visiter.")
public record OptimizeRequest(
        @Schema(description = "Depot : point de depart ET d'arrivee commun a tous les vehicules.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Valid Coordinate depot,

        @Schema(description = "Nombre de vehicules. 1 = optimisation d'ordre simple (TSP). Defaut : 1.",
                example = "1", defaultValue = "1")
        Integer vehicleCount,

        @Schema(description = "Capacite par vehicule (memes unites que `demand`). Null = illimite.",
                example = "10", nullable = true)
        Integer vehicleCapacity,

        @Schema(description = "Heure de depart du depot (ISO-8601). Sert a calculer les heures d'arrivee/depart "
                + "a chaque point. Si absent : heure courante du serveur.",
                example = "2026-06-15T08:00:00", nullable = true)
        LocalDateTime departureTime,

        @Schema(description = "[Deprecie : preferer geometryFormat] Inclure la geometrie de chaque segment. "
                + "Ignore si geometryFormat est fourni. true -> POINTS, false -> NONE.",
                example = "true", nullable = true)
        Boolean includeGeometry,

        @Schema(description = "Format de la geometrie de chaque segment : POINTS (defaut), POLYLINE (compact, "
                + "recommande pour les longues tournees) ou NONE.",
                defaultValue = "POINTS", nullable = true)
        GeometryFormat geometryFormat,

        @Schema(description = "Points a visiter (au moins 1).", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Valid List<VisitDto> visits) {

    public int resolvedVehicleCount() {
        return vehicleCount == null || vehicleCount < 1 ? 1 : vehicleCount;
    }

    public GeometryFormat resolvedGeometryFormat() {
        if (geometryFormat != null) {
            return geometryFormat;
        }
        if (includeGeometry != null) {
            return includeGeometry ? GeometryFormat.POINTS : GeometryFormat.NONE;
        }
        return GeometryFormat.POINTS;
    }
}
