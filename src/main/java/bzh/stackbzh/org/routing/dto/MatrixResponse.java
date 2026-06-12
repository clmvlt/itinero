package bzh.stackbzh.org.routing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Matrice NxN des temps de trajet.")
public record MatrixResponse(
        @Schema(description = "Nombre de points (N).", example = "3")
        int size,
        @Schema(description = "Temps de trajet en secondes : durationsSeconds[i][j] = i -> j. Diagonale = 0.",
                example = "[[0,4380,9200],[4350,0,8100],[9150,8050,0]]")
        long[][] durationsSeconds) {
}
