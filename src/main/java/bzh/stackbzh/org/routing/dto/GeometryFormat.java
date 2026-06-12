package bzh.stackbzh.org.routing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        Format de la geometrie :
        - POINTS   : liste de points [lat,lon] (champ `geometry`). Lisible mais volumineux.
        - POLYLINE : chaine encodee (champ `geometryPolyline`), algorithme Google/OSRM precision 5 \
        (~1 m), bien plus compacte pour les longs trajets. Decodable avec @mapbox/polyline, etc.
        - NONE     : pas de geometrie (reponse la plus legere).""")
public enum GeometryFormat {
    POINTS,
    POLYLINE,
    NONE
}
