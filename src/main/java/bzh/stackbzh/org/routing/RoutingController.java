package bzh.stackbzh.org.routing;

import bzh.stackbzh.org.routing.dto.Coordinate;
import bzh.stackbzh.org.routing.dto.GeometryFormat;
import bzh.stackbzh.org.routing.dto.MatrixRequest;
import bzh.stackbzh.org.routing.dto.MatrixResponse;
import bzh.stackbzh.org.routing.dto.RouteRequest;
import bzh.stackbzh.org.routing.dto.RouteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/routing")
@Tag(name = "Routing", description = """
        Calcul d'itineraires et de matrices de temps via GraphHopper embarque \
        (extrait OpenStreetMap de la France). Necessite que le graphe routier soit charge \
        (voir GET /routing/status). Sinon : 503.""")
public class RoutingController {

    private final RoutingEngine engine;
    private final MatrixService matrixService;

    public RoutingController(RoutingEngine engine, MatrixService matrixService) {
        this.engine = engine;
        this.matrixService = matrixService;
    }

    @GetMapping("/status")
    @Operation(summary = "Etat du moteur de routing",
            description = "Indique si le graphe routier est charge (`ready=true`) et le profil utilise (`car`). "
                    + "A interroger avant tout appel de routing : si `ready=false`, les autres endpoints renvoient 503.")
    @ApiResponse(responseCode = "200", description = "Etat retourne",
            content = @Content(schema = @Schema(example = "{\"ready\": true, \"profile\": \"car\"}")))
    public Map<String, Object> status() {
        return Map.of("ready", engine.isReady(), "profile", engine.profileName());
    }

    @PostMapping("/route")
    @Operation(summary = "Itineraire point a point ou multi-points ordonne",
            description = """
                    Calcule un itineraire routier. Deux modes (cf. corps de requete) :

                    - **point a point** : fournir `from` et `to` ;
                    - **multi-points ordonne** : fournir `points` (>= 2 elements). L'itineraire passe \
                    par tous les points DANS L'ORDRE FOURNI (aucune reoptimisation : pour reordonner, \
                    utiliser POST /optimization/optimize). Chaque paire consecutive est routee, puis les \
                    troncons sont concatenes en une trace continue (sans point en double aux jonctions).

                    Retour : `distanceMeters` et `durationSeconds` cumules sur tout le parcours, plus la \
                    geometrie. `geometry` (liste [lat,lon]) est renseigne si `geometryFormat=POINTS` ; \
                    `geometryPolyline` (encodage Google/OSRM, precision 5, ordre lat/lon) est renseigne \
                    pour POINTS comme POLYLINE (null seulement si NONE).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Itineraire calcule"),
            @ApiResponse(responseCode = "400", description = "Corps invalide : ni (`from`+`to`) ni `points` (>= 2) fournis, "
                    + "ou coordonnees invalides", content = @Content),
            @ApiResponse(responseCode = "503", description = "Moteur de routing indisponible (graphe non charge)", content = @Content)
    })
    public RouteResponse route(@Valid @RequestBody RouteRequest request) {
        List<Coordinate> waypoints = request.waypoints();
        GeometryFormat format = request.resolvedGeometryFormat();

        double totalDistance = 0;
        long totalDuration = 0;
        List<double[]> mergedGeometry = new ArrayList<>();

        for (int i = 0; i + 1 < waypoints.size(); i++) {
            Coordinate a = waypoints.get(i);
            Coordinate b = waypoints.get(i + 1);
            RoutingEngine.Leg leg = engine.route(a.lat(), a.lon(), b.lat(), b.lon());
            totalDistance += leg.distanceMeters();
            totalDuration += leg.durationSeconds();
            appendGeometry(mergedGeometry, leg.geometry());
        }

        double[][] fullGeometry = mergedGeometry.toArray(new double[0][]);
        double[][] points = format == GeometryFormat.POINTS ? fullGeometry : null;
        String polyline = format == GeometryFormat.NONE ? null : GeometryEncoder.encodePolyline(fullGeometry);
        return new RouteResponse(totalDistance, totalDuration, format.name(), points, polyline);
    }

    private static void appendGeometry(List<double[]> accumulator, double[][] segment) {
        if (segment == null) {
            return;
        }
        int start = accumulator.isEmpty() ? 0 : 1;
        for (int k = start; k < segment.length; k++) {
            accumulator.add(segment[k]);
        }
    }

    @PostMapping("/matrix")
    @Operation(summary = "Matrice des temps de trajet",
            description = """
                    Calcule la matrice NxN des temps de trajet (secondes) entre tous les points fournis.
                    `durationsSeconds[i][j]` = temps pour aller du point i au point j (peut etre asymetrique \
                    a cause des sens uniques ; la diagonale vaut 0).
                    Utilise en amont d'une optimisation. Cout : N*N routages (rapide grace aux Contraction Hierarchies).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matrice calculee"),
            @ApiResponse(responseCode = "400", description = "Liste de points vide ou invalide", content = @Content),
            @ApiResponse(responseCode = "503", description = "Moteur de routing indisponible", content = @Content)
    })
    public MatrixResponse matrix(@Valid @RequestBody MatrixRequest request) {
        List<double[]> points = request.points().stream()
                .map(c -> new double[]{c.lat(), c.lon()})
                .toList();
        long[][] durations = matrixService.timeMatrixSeconds(points);
        return new MatrixResponse(points.size(), durations);
    }
}
