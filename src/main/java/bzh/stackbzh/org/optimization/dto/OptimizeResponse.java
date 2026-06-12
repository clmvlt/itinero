package bzh.stackbzh.org.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Resultat d'optimisation : tournees ordonnees par vehicule, avec distances, "
        + "heures d'arrivee et geometrie du trace.")
public record OptimizeResponse(
        @Schema(description = "Score Timefold (`<dur>hard/<soft>soft`). `0hard` = contraintes dures respectees.",
                example = "0hard/-12450soft")
        String score,
        @Schema(description = "Temps de conduite total cumule sur tous les vehicules, en secondes.", example = "12450")
        long totalDrivingTimeSeconds,
        @Schema(description = "Distance totale parcourue par tous les vehicules, en metres.", example = "184300.0")
        double totalDistanceMeters,
        @Schema(description = "Une entree par vehicule.")
        List<RouteDto> routes) {

    @Schema(description = "Tournee d'un vehicule : visites dans l'ordre, depot -> points -> depot.")
    public record RouteDto(
            @Schema(description = "Identifiant du vehicule.", example = "vehicle-0")
            String vehicleId,
            @Schema(description = "Heure de depart du depot.", example = "2026-06-15T08:00:00")
            LocalDateTime departureTime,
            @Schema(description = "Heure de retour au depot (fin de tournee).", example = "2026-06-15T11:27:30")
            LocalDateTime returnTime,
            @Schema(description = "Temps de conduite total de la tournee, en secondes.", example = "12450")
            long drivingTimeSeconds,
            @Schema(description = "Temps de service total (arrets) de la tournee, en secondes.", example = "1500")
            long serviceTimeSeconds,
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

    @Schema(description = "Un arret de la tournee, avec le segment depuis le point precedent et les cumuls.")
    public record StopDto(
            @Schema(description = "Identifiant du point visite.", example = "A")
            String visitId,
            @Schema(description = "Libelle du point.", example = "Client A")
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
            @Schema(description = "Heure d'arrivee a ce point.", example = "2026-06-15T09:13:00")
            LocalDateTime arrivalTime,
            @Schema(description = "Heure de depart de ce point (arrivee + duree de service).", example = "2026-06-15T09:18:00")
            LocalDateTime departureTime,
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
