package bzh.stackbzh.org.routing;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MatrixService {

    private final RoutingEngine engine;

    public MatrixService(RoutingEngine engine) {
        this.engine = engine;
    }

    public long[][] timeMatrixSeconds(List<double[]> points) {
        int n = points.size();
        long[][] matrix = new long[n][n];
        for (int i = 0; i < n; i++) {
            double[] from = points.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    matrix[i][j] = 0;
                    continue;
                }
                double[] to = points.get(j);
                matrix[i][j] = engine.travelTimeSeconds(from[0], from[1], to[0], to[1]);
            }
        }
        return matrix;
    }
}
