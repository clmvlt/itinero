package bzh.stackbzh.org.routing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Itineraire calcule entre deux points.")
public record RouteResponse(
        @Schema(description = "Distance totale en metres.", example = "104532.0")
        double distanceMeters,
        @Schema(description = "Duree totale en secondes.", example = "4380")
        long durationSeconds,
        @Schema(description = "Format de geometrie demande : POINTS, POLYLINE ou NONE. "
                + "Rappel : `geometryPolyline` est renseigne pour POINTS et POLYLINE.", example = "POINTS")
        String geometryFormat,
        @Schema(description = "Trace en liste de points [latitude, longitude]. Non nul uniquement si format = POINTS. "
                + "Sur un trajet multi-points, c'est la trace continue de bout en bout (sans doublon aux jonctions).",
                example = "[[48.1173,-1.6778],[48.0,-1.7],[47.2184,-1.5536]]", nullable = true)
        double[][] geometry,
        @Schema(description = "Trace en polyligne encodee. Renseigne (non nul) pour les formats POINTS et POLYLINE ; "
                + "null uniquement si format = NONE. Encodage : algorithme Google/OSRM, precision 5 (facteur 1e5), "
                + "deltas dans l'ordre (latitude, longitude). Decodable par @mapbox/polyline. "
                + "Sur un trajet multi-points, couvre tout le parcours.",
                example = "ydlrHnwfA~A_@dGsT", nullable = true)
        String geometryPolyline) {
}
