package bzh.stackbzh.org.routing;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.IntStream;

@Service
public class MatrixService {

    private final RoutingEngine engine;

    public MatrixService(RoutingEngine engine) {
        this.engine = engine;
    }

    public long[][] timeMatrixSeconds(List<double[]> points) {
        int n = points.size();
        long[][] matrix = new long[n][n];
        IntStream.range(0, n).parallel().forEach(i -> {
            double[] from = points.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    matrix[i][j] = 0;
                    continue;
                }
                double[] to = points.get(j);
                matrix[i][j] = engine.travelTimeSeconds(from[0], from[1], to[0], to[1]);
            }
        });
        return matrix;
    }
}
