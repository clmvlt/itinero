package bzh.stackbzh.org.routing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "Demande de matrice de temps entre N points.")
public record MatrixRequest(
        @Schema(description = "Liste des points (au moins 1). L'ordre est conserve dans la matrice resultat.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Valid List<Coordinate> points) {
}
