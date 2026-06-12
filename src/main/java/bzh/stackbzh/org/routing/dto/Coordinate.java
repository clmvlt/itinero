package bzh.stackbzh.org.routing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Point geographique en coordonnees WGS84.")
public record Coordinate(
        @Schema(description = "Latitude en degres decimaux.", example = "48.1173", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Double lat,
        @Schema(description = "Longitude en degres decimaux.", example = "-1.6778", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Double lon) {
}
