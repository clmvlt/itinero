package bzh.stackbzh.org.optimization.dto;

import bzh.stackbzh.org.routing.dto.Coordinate;
import bzh.stackbzh.org.routing.dto.GeometryFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Demande d'optimisation de tournee : depot commun, vehicules, heure de depart estimee, "
        + "points a visiter (avec fenetres horaires optionnelles).")
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

        @Schema(description = "Heure de depart ESTIMEE du depot (ISO-8601 LocalDateTime, heure locale, sans fuseau). "
                + "C'est l'origine des temps de toute la tournee : les heures d'arrivee/depart de chaque arret en "
                + "decoulent ET c'est par rapport a elle que le solveur evalue les fenetres horaires "
                + "(`timeWindowStart`/`timeWindowEnd` des visites). A renseigner des qu'au moins une visite a une "
                + "fenetre horaire, sinon l'heure courante du serveur est utilisee (ce qui peut rendre des fenetres "
                + "infaisables si la requete est calculee bien avant le depart reel).",
                example = "2026-06-15T20:00:00", nullable = true)
        LocalDateTime departureTime,

        @Schema(description = "Attente MAXIMALE toleree devant une fenetre horaire pas encore ouverte, en secondes. "
                + "Si le vehicule arriverait en avance de plus que cette valeur sur `timeWindowStart`, l'arret est "
                + "considere comme NE CORRESPONDANT PAS au creneau (contrainte dure) : le solveur reordonne la "
                + "tournee pour l'eviter et, si aucun ordre ne le permet, la tournee est renvoyee avec "
                + "`feasible=false`, `stops[].timeWindowStatus=WAITING_TOO_LONG` et "
                + "`stops[].excessiveWaitingSeconds` > 0. Evite les tournees ou le vehicule poireaute 3 h devant "
                + "un client. Omis (null) = valeur serveur `app.optimization.max-waiting-seconds` (3600 s = 1 h "
                + "par defaut). `0` = DESACTIVE (attente illimitee, seulement minimisee en soft). La valeur "
                + "effectivement appliquee est renvoyee dans `maxWaitingSeconds` de la reponse.",
                example = "3600", defaultValue = "3600", nullable = true, minimum = "0")
        @Min(0) Integer maxWaitingSeconds,

        @Schema(description = "[Deprecie : preferer geometryFormat] Inclure la geometrie de chaque segment. "
                + "Ignore si geometryFormat est fourni. true -> POINTS, false -> NONE.",
                example = "true", nullable = true)
        Boolean includeGeometry,

        @Schema(description = "Format de la geometrie de chaque segment : POINTS (defaut), POLYLINE (compact, "
                + "recommande pour les longues tournees) ou NONE.",
                defaultValue = "POINTS", nullable = true)
        GeometryFormat geometryFormat,

        @Schema(description = "Points a visiter (au moins 1). Chaque point peut porter une fenetre horaire optionnelle.",
                requiredMode = Schema.RequiredMode.REQUIRED)
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
