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

@Schema(description = "Demande de repartition : un depot commun, un NOMBRE de tournees voulu et une liste de points "
        + "(typiquement plusieurs dizaines a plusieurs centaines). L'API repartit elle-meme les points entre les "
        + "tournees en EQUILIBRANT LES DUREES (conduite + service + attentes) et en formant des zones geographiques "
        + "coherentes, puis ordonne chaque tournee. Aucune capacite n'est necessaire. Les fenetres horaires "
        + "optionnelles par point sont respectees comme sur `/optimize`. Les points sont fournis sous deux formes "
        + "combinables : `visits` (arrets independants) et `shipments` (missions appairees chargement -> "
        + "enlevement, jamais coupees entre deux tournees).")
public record DispatchRequest(
        @Schema(description = "Depot : point de depart ET d'arrivee commun a toutes les tournees.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Valid Coordinate depot,

        @Schema(description = "Nombre de tournees (= de vehicules) a produire. REQUIS, >= 1. Le solveur utilise "
                + "tous les vehicules des que les points le permettent (avec plus de vehicules que de points, les "
                + "vehicules excedentaires ont une tournee vide). 1 = simple optimisation d'ordre (TSP).",
                example = "5", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
        @NotNull @Min(1) Integer vehicleCount,

        @Schema(description = "Capacite par vehicule (memes unites que `demand` des visites). FACULTATIF : la "
                + "repartition n'en a pas besoin (elle se fait sur les durees). Si fournie, elle s'ajoute comme "
                + "contrainte DURE (une tournee ne peut pas depasser cette charge). Null = illimitee.",
                example = "40", nullable = true)
        Integer vehicleCapacity,

        @Schema(description = "Heure de depart ESTIMEE du depot, commune a toutes les tournees (ISO-8601 "
                + "LocalDateTime, heure locale, sans fuseau). Origine des temps : toutes les heures de la reponse "
                + "en decoulent et les fenetres horaires (`timeWindowStart`/`timeWindowEnd` des visites) sont "
                + "evaluees par rapport a elle. Omise = heure courante du serveur. A TOUJOURS renseigner des "
                + "qu'au moins une visite a une fenetre horaire.",
                example = "2026-06-15T08:00:00", nullable = true)
        LocalDateTime departureTime,

        @Schema(description = "Attente MAXIMALE toleree devant une fenetre horaire pas encore ouverte, en secondes "
                + "(meme semantique que sur `/optimize`). Au-dela, l'arret est considere comme ne correspondant pas "
                + "au creneau (contrainte dure -> `feasible=false`, `timeWindowStatus=WAITING_TOO_LONG` si aucune "
                + "repartition ne l'evite). Omis = defaut serveur `app.optimization.max-waiting-seconds` (3600 s). "
                + "`0` = desactive (attente illimitee, seulement minimisee).",
                example = "3600", defaultValue = "3600", nullable = true, minimum = "0")
        @Min(0) Integer maxWaitingSeconds,

        @Schema(description = "Temps de RESOLUTION alloue au solveur, en secondes. La requete HTTP dure au moins ce "
                + "temps (plus le calcul de la matrice : ~101x101 routages pour 100 points, quelques secondes). "
                + "Plus il est long, meilleure est la repartition ; pour ~100 points / 5 tournees, 10 s donne un "
                + "resultat correct et 20-30 s un tres bon resultat. Omis = defaut serveur "
                + "`app.optimization.dispatch.default-solving-seconds` (10 s). Plafonne SILENCIEUSEMENT a "
                + "`app.optimization.dispatch.max-solving-seconds` (60 s) : la valeur effectivement appliquee est "
                + "renvoyee dans `solvingTimeSeconds`.",
                example = "20", defaultValue = "10", nullable = true, minimum = "1")
        @Min(1) Integer maxSolvingSeconds,

        @Schema(description = "Format de la geometrie des segments et des traces de tournee : POINTS (defaut), "
                + "POLYLINE (compact, recommande : 100 points = 100 segments a tracer) ou NONE (le plus leger).",
                defaultValue = "POINTS", nullable = true)
        GeometryFormat geometryFormat,

        @Schema(description = "Points a repartir puis visiter (typiquement 20 a 300), independants les uns des "
                + "autres. Chaque point peut porter une duree de service et une fenetre horaire optionnelles. "
                + "`demand` n'est utile que si `vehicleCapacity` est fourni. Peut etre vide (ou absent) si "
                + "`shipments` est fourni, mais la requete doit contenir AU MOINS un point ou une mission.",
                nullable = true)
        @Valid List<VisitDto> visits,

        @Schema(description = "MISSIONS APPAIREES (chargement -> enlevement), facultatives. Une mission n'est "
                + "JAMAIS coupee entre deux tournees : ses deux arrets partent dans la meme, le chargement "
                + "d'abord. La repartition equilibree se fait donc sur des missions entieres. Peuvent etre "
                + "melangees librement avec `visits`. Omises = comportement historique, a l'identique.",
                nullable = true)
        @Valid List<ShipmentDto> shipments) {

    public GeometryFormat resolvedGeometryFormat() {
        return geometryFormat != null ? geometryFormat : GeometryFormat.POINTS;
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
}
