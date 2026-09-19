package bzh.stackbzh.org.optimization.dto;

import bzh.stackbzh.org.routing.dto.Coordinate;
import bzh.stackbzh.org.routing.dto.GeometryFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Demande d'optimisation de tournee : depot commun, vehicules, heure de depart estimee, et "
        + "points a visiter, fournis sous deux formes combinables — `visits` (arrets independants, ordonnes "
        + "librement) et `shipments` (missions appairees chargement -> enlevement, servies par le meme vehicule, "
        + "chargement d'abord). Au moins un point ou une mission est requis.")
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

        @Schema(description = "Temps de RESOLUTION alloue au solveur, en secondes. FACULTATIF et sans effet sur "
                + "une tournee classique : omis, le serveur applique sa terminaison globale "
                + "(`timefold.solver.termination.spent-limit`, 1 s), ce qui suffit pour ordonner des points "
                + "independants. En revanche, DES QUE la requete contient des `shipments`, le probleme est plus "
                + "difficile (l'etat initial ignore la precedence, le solveur doit d'abord la reparer) et un budget "
                + "plus long est applique : valeur de ce champ, sinon defaut serveur "
                + "`app.optimization.shipments.default-solving-seconds` (5 s), plafonne SILENCIEUSEMENT a "
                + "`app.optimization.shipments.max-solving-seconds` (60 s). La requete HTTP dure alors au moins ce "
                + "temps. La valeur reellement appliquee est renvoyee dans `solvingTimeSeconds`.",
                example = "5", nullable = true, minimum = "1")
        @Min(1) Integer maxSolvingSeconds,

        @Schema(description = "Points a visiter, independants les uns des autres : le solveur est libre de les "
                + "ordonner comme il veut. Chaque point peut porter une duree de service et une fenetre horaire "
                + "optionnelles. Peut etre vide (ou absent) si `shipments` est fourni, mais la requete doit "
                + "contenir AU MOINS un point ou une mission.",
                nullable = true)
        @Valid List<VisitDto> visits,

        @Schema(description = "MISSIONS APPAIREES (chargement -> enlevement), facultatives. A utiliser quand une "
                + "marchandise doit etre chargee a un endroit puis deposee a un autre : l'API garantit alors que "
                + "les deux arrets sont sur le MEME vehicule et que le chargement passe AVANT l'enlevement. "
                + "Peuvent etre melangees librement avec `visits`. Omises = comportement historique, a l'identique.",
                nullable = true)
        @Valid List<ShipmentDto> shipments) {

    public int resolvedVehicleCount() {
        return vehicleCount == null || vehicleCount < 1 ? 1 : vehicleCount;
    }

    public List<VisitDto> resolvedVisits() {
        return visits != null ? visits : List.of();
    }

    public List<ShipmentDto> resolvedShipments() {
        return shipments != null ? shipments : List.of();
    }

    @AssertTrue(message = "la requete doit contenir au moins une visite ou une mission (visits ou shipments)")
    @Schema(hidden = true)
    public boolean isNotEmpty() {
        return !resolvedVisits().isEmpty() || !resolvedShipments().isEmpty();
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
