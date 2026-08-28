package bzh.stackbzh.org.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Resultat d'optimisation : tournees ordonnees par vehicule, avec distances, "
        + "heures d'arrivee/depart, respect des fenetres horaires et geometrie du trace.")
public record OptimizeResponse(
        @Schema(description = "Score Timefold (`<dur>hard/<soft>soft`). `0hard` = contraintes dures respectees "
                + "(capacite ET fenetres horaires). Le `soft` = temps de conduite + temps d'attente (s), minimise.",
                example = "0hard/-12450soft")
        String score,
        @Schema(description = "true si TOUTES les contraintes dures sont respectees : aucune capacite depassee, "
                + "aucun arret en retard sur sa fenetre horaire (`lateSeconds` = 0 partout) et aucune attente "
                + "au-dela de `maxWaitingSeconds` (`excessiveWaitingSeconds` = 0 partout). false = la tournee est "
                + "quand meme renvoyee (meilleure solution trouvee) mais au moins une contrainte est violee : "
                + "inspecter `timeWindowViolations` et les `stops[].timeWindowStatus`.", example = "true")
        boolean feasible,
        @Schema(description = "Nombre d'arrets dont la fenetre horaire n'est PAS respectee : arrivee apres "
                + "`timeWindowEnd` (`timeWindowStatus=LATE`) OU attente avant `timeWindowStart` superieure a "
                + "`maxWaitingSeconds` (`timeWindowStatus=WAITING_TOO_LONG`). 0 si toutes les fenetres sont tenues "
                + "ou si aucune fenetre n'a ete fournie.", example = "0")
        int timeWindowViolations,
        @Schema(description = "Attente maximale toleree devant une fenetre horaire effectivement appliquee, en "
                + "secondes (valeur de la requete ou defaut serveur). null = pas de limite (desactive par `0`).",
                example = "900", nullable = true)
        Integer maxWaitingSeconds,
        @Schema(description = "Temps de conduite total cumule sur tous les vehicules, en secondes.", example = "12450")
        long totalDrivingTimeSeconds,
        @Schema(description = "Distance totale parcourue par tous les vehicules, en metres.", example = "184300.0")
        double totalDistanceMeters,
        @Schema(description = "Une entree par vehicule.")
        List<RouteDto> routes,
        @Schema(description = "Visites EXCLUES de l'optimisation car non rattachables au reseau routier "
                + "(coordonnees en mer, hors de la zone OSM couverte, ou trop eloignees de toute route selon "
                + "`app.routing.max-snap-distance-meters`). Ces points ne figurent dans AUCUNE tournee : ils sont "
                + "ignores pour ne pas faire echouer toute la requete. Liste vide si tous les points ont ete pris "
                + "en compte. Le client DOIT verifier ce tableau et, le cas echeant, signaler/corriger ces points.")
        List<SkippedVisitDto> skippedVisits) {

    @Schema(description = "Visite ecartee de l'optimisation, avec le motif. N'apparait dans aucune tournee.")
    public record SkippedVisitDto(
            @Schema(description = "Identifiant de la visite (celui fourni, ou auto-genere `v<index>`).", example = "A")
            String visitId,
            @Schema(description = "Libelle de la visite (tel que fourni).", example = "Client A", nullable = true)
            String name,
            @Schema(description = "Latitude fournie.", example = "47.2184")
            double lat,
            @Schema(description = "Longitude fournie.", example = "-1.5536")
            double lon,
            @Schema(description = "Motif d'exclusion : `UNROUTABLE` (aucune route trouvable a proximite : point en "
                    + "mer, hors zone couverte, ou reseau deconnecte) ou `TOO_FAR` (route la plus proche au-dela "
                    + "du seuil `app.routing.max-snap-distance-meters`).",
                    example = "TOO_FAR", allowableValues = {"UNROUTABLE", "TOO_FAR"})
            String reason,
            @Schema(description = "Distance (m) jusqu'a la route la plus proche. Renseignee pour `TOO_FAR` ; "
                    + "null pour `UNROUTABLE` (aucune route trouvee).", example = "1830.0", nullable = true)
            Double snapDistanceMeters) {
    }

    @Schema(description = "Tournee d'un vehicule : visites dans l'ordre, depot -> points -> depot.")
    public record RouteDto(
            @Schema(description = "Identifiant du vehicule.", example = "vehicle-0")
            String vehicleId,
            @Schema(description = "Heure de depart du depot (= `departureTime` de la requete).", example = "2026-06-15T20:00:00")
            LocalDateTime departureTime,
            @Schema(description = "Heure de retour au depot (fin de tournee), attentes incluses.", example = "2026-06-15T23:27:30")
            LocalDateTime returnTime,
            @Schema(description = "Temps de conduite total de la tournee, en secondes.", example = "12450")
            long drivingTimeSeconds,
            @Schema(description = "Temps de service total (arrets) de la tournee, en secondes.", example = "1500")
            long serviceTimeSeconds,
            @Schema(description = "Temps d'attente total de la tournee, en secondes : somme des `waitingSeconds` des "
                    + "arrets (vehicule arrive avant l'ouverture d'une fenetre horaire). 0 sans fenetres.", example = "600")
            long waitingTimeSeconds,
            @Schema(description = "Distance totale de la tournee, en metres.", example = "184300.0")
            double distanceMeters,
            @Schema(description = "Somme des demandes des visites de la tournee.", example = "2")
            int totalDemand,
            @Schema(description = "Arrets dans l'ordre optimal de passage.")
            List<StopDto> stops,
            @Schema(description = "Segment de retour du dernier point vers le depot.")
            LegDto returnLeg,
            @Schema(description = "Trace COMPLETE de la tournee (depot -> arrets dans l'ordre -> depot) en liste de "
                    + "points [lat,lon], continue et sans doublon aux jonctions. Non nul uniquement si "
                    + "geometryFormat = POINTS. A privilegier pour tracer toute la tournee d'un seul trait : "
                    + "les polylignes par segment ne sont pas concatenables.",
                    example = "[[48.1173,-1.6778],[48.05,-1.7],[47.2184,-1.5536]]", nullable = true)
            double[][] geometry,
            @Schema(description = "Trace COMPLETE de la tournee (depot -> arrets -> depot) en polyligne encodee "
                    + "(Google/OSRM, precision 5, ordre lat/lon). Renseignee (non nul) pour POINTS et POLYLINE ; "
                    + "null uniquement si geometryFormat = NONE. Champ a utiliser pour afficher le trace global "
                    + "de la tournee (les `legFromPrevious.geometryPolyline` ne se concatenent pas entre eux).",
                    example = "ydlrHnwfA~A_@dGsT", nullable = true)
            String geometryPolyline) {
    }

    @Schema(description = "Un arret de la tournee, avec le segment depuis le point precedent, les cumuls, "
            + "les heures (arrivee, debut de service, depart) et le respect de la fenetre horaire.")
    public record StopDto(
            @Schema(description = "Identifiant du point visite.", example = "A")
            String visitId,
            @Schema(description = "Libelle du point.", example = "Pharmacie du Centre")
            String name,
            @Schema(description = "Latitude.", example = "47.2184")
            double lat,
            @Schema(description = "Longitude.", example = "-1.5536")
            double lon,
            @Schema(description = "Segment parcouru depuis le point precedent (ou le depot) jusqu'a ce point.")
            LegDto legFromPrevious,
            @Schema(description = "Distance cumulee depuis le depot jusqu'a ce point, en metres.", example = "52300.0")
            double cumulativeDistanceMeters,
            @Schema(description = "Temps de conduite cumule depuis le depot jusqu'a ce point, en secondes.", example = "4380")
            long cumulativeDrivingSeconds,
            @Schema(description = "Heure d'ARRIVEE physique au point (depart du point precedent + trajet).",
                    example = "2026-06-15T20:52:00")
            LocalDateTime arrivalTime,
            @Schema(description = "Heure de DEBUT du service/livraison : = `arrivalTime`, ou = `timeWindowStart` si le "
                    + "vehicule est arrive en avance et a attendu l'ouverture de la fenetre.",
                    example = "2026-06-15T21:00:00")
            LocalDateTime serviceStartTime,
            @Schema(description = "Heure de depart de ce point (`serviceStartTime` + duree de service).",
                    example = "2026-06-15T21:05:00")
            LocalDateTime departureTime,
            @Schema(description = "Fenetre horaire demandee (debut), telle que fournie. null si non fournie.",
                    example = "2026-06-15T21:00:00", nullable = true)
            LocalDateTime timeWindowStart,
            @Schema(description = "Fenetre horaire demandee (fin = heure limite d'arrivee), telle que fournie. "
                    + "null si non fournie.", example = "2026-06-15T23:00:00", nullable = true)
            LocalDateTime timeWindowEnd,
            @Schema(description = "Attente sur place avant l'ouverture de la fenetre, en secondes "
                    + "(`serviceStartTime` - `arrivalTime`). 0 si pas d'attente / pas de fenetre. Si elle depasse "
                    + "`maxWaitingSeconds`, l'arret est en VIOLATION (`timeWindowStatus=WAITING_TOO_LONG`) ; les heures "
                    + "restent calculees comme si le vehicule attendait l'ouverture.", example = "480")
            long waitingSeconds,
            @Schema(description = "Part de l'attente qui depasse `maxWaitingSeconds` (`waitingSeconds` - limite si "
                    + "positif). 0 = attente toleree (ou pas de limite). > 0 = VIOLATION : le solveur n'a pas trouve "
                    + "d'ordre evitant d'arriver aussi tot ; l'arret ne correspond pas au creneau demande "
                    + "(voir `feasible`).", example = "0")
            long excessiveWaitingSeconds,
            @Schema(description = "Retard par rapport a `timeWindowEnd`, en secondes (`arrivalTime` - `timeWindowEnd` "
                    + "si positif). 0 = fenetre respectee (ou pas de fenetre). > 0 = VIOLATION : le solveur n'a pas "
                    + "trouve d'ordre permettant d'arriver a temps (voir `feasible`).", example = "0")
            long lateSeconds,
            @Schema(description = "Respect de la fenetre horaire de cet arret : `OK` (fenetre tenue, ou pas de "
                    + "fenetre), `LATE` (arrivee apres `timeWindowEnd`, voir `lateSeconds`), `WAITING_TOO_LONG` "
                    + "(arrivee trop en avance : attente > `maxWaitingSeconds`, voir `excessiveWaitingSeconds`). "
                    + "Tout statut autre que `OK` compte dans `timeWindowViolations` et rend `feasible=false`.",
                    example = "OK", allowableValues = {"OK", "LATE", "WAITING_TOO_LONG"})
            String timeWindowStatus,
            @Schema(description = "Demande consommee a ce point.", example = "1")
            int demand) {
    }

    @Schema(description = "Un segment routier : distance, duree et geometrie (selon geometryFormat).")
    public record LegDto(
            @Schema(description = "Distance du segment, en metres.", example = "52300.0")
            double distanceMeters,
            @Schema(description = "Duree de conduite du segment, en secondes.", example = "4380")
            long durationSeconds,
            @Schema(description = "Trace en points [lat,lon]. Non nul uniquement si geometryFormat = POINTS.",
                    example = "[[48.1173,-1.6778],[48.0,-1.7],[47.2184,-1.5536]]", nullable = true)
            double[][] geometry,
            @Schema(description = "Trace en polyligne encodee (precision 5). Non nul uniquement si geometryFormat = POLYLINE.",
                    example = "ydlrHnwfA~A_@dGsT", nullable = true)
            String geometryPolyline) {
    }
}
