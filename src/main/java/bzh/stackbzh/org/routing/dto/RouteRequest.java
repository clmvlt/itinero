package bzh.stackbzh.org.routing.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

import java.util.List;

@Schema(description = """
        Demande d'itineraire. Deux modes mutuellement exclusifs :
        - point a point : renseigner `from` et `to` ;
        - multi-points ordonne : renseigner `points` (>= 2 elements). L'itineraire passe par \
        tous les points DANS L'ORDRE FOURNI (aucune reoptimisation ; pour reordonner, utiliser \
        /optimization/optimize). Si `points` est present, il prime sur `from`/`to`.""")
public record RouteRequest(
        @Schema(description = "Point de depart (mode point a point). Requis si `points` est absent.",
                nullable = true)
        @Valid Coordinate from,
        @Schema(description = "Point d'arrivee (mode point a point). Requis si `points` est absent.",
                nullable = true)
        @Valid Coordinate to,
        @Schema(description = "Liste ordonnee des points de passage (mode multi-points). >= 2 elements. "
                + "L'ordre est respecte tel quel ; distance/duree sont cumulees et la geometrie est "
                + "une trace continue de bout en bout. Prioritaire sur `from`/`to` si fournie.",
                nullable = true)
        @Valid List<Coordinate> points,
        @Schema(description = "Format de la geometrie renvoyee. Defaut : POINTS. "
                + "Utiliser POLYLINE pour les longs trajets (reponse compacte). "
                + "Note : `geometryPolyline` est de toute facon renseigne sauf si NONE.",
                defaultValue = "POINTS", nullable = true)
        GeometryFormat geometryFormat) {

    public GeometryFormat resolvedGeometryFormat() {
        return geometryFormat != null ? geometryFormat : GeometryFormat.POINTS;
    }

    @JsonIgnore
    public List<Coordinate> waypoints() {
        if (points != null && !points.isEmpty()) {
            return points;
        }
        if (from != null && to != null) {
            return List.of(from, to);
        }
        return List.of();
    }

    @JsonIgnore
    @AssertTrue(message = "Fournir soit 'from' et 'to', soit 'points' avec au moins 2 elements.")
    public boolean isItineraryValid() {
        if (points != null && !points.isEmpty()) {
            return points.size() >= 2;
        }
        return from != null && to != null;
    }
}
