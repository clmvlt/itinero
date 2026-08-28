package bzh.stackbzh.org.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

@Schema(description = "Point a visiter dans une tournee. La fenetre horaire (`timeWindowStart`/`timeWindowEnd`) est "
        + "OPTIONNELLE : les deux absents = aucune contrainte horaire (« pas d'importance »). On peut ne donner "
        + "qu'une borne (ex : seulement `timeWindowEnd` = « avant 23h », seulement `timeWindowStart` = « pas avant 21h »).")
public record VisitDto(
        @Schema(description = "Identifiant du point (optionnel ; auto-genere si absent).", example = "A")
        String id,
        @Schema(description = "Libelle lisible (optionnel).", example = "Pharmacie du Centre")
        String name,
        @Schema(description = "Latitude WGS84.", example = "47.2184", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Double lat,
        @Schema(description = "Longitude WGS84.", example = "-1.5536", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Double lon,
        @Schema(description = "Demande / charge consommee a ce point (optionnel ; defaut 0). Comparee a la capacite du vehicule.",
                example = "1")
        Integer demand,
        @Schema(description = "Duree d'arret / de service a ce point en secondes (optionnel ; defaut 0). "
                + "Decale les heures d'arrivee des points suivants.", example = "300")
        Integer serviceDurationSeconds,
        @Schema(description = "Debut de la fenetre horaire de livraison (ISO-8601 LocalDateTime, MEME jour/reference "
                + "que `departureTime` de la requete). Si le vehicule arrive AVANT cette heure, il ATTEND sur place "
                + "(attente comptee dans `waitingSeconds` et minimisee par le solveur). Null = pas de borne basse "
                + "(on peut livrer des le depart).",
                example = "2026-06-15T21:00:00", nullable = true)
        LocalDateTime timeWindowStart,
        @Schema(description = "Fin de la fenetre horaire : heure LIMITE d'ARRIVEE au point (ISO-8601 LocalDateTime). "
                + "Contrainte DURE du solveur : il fait tout pour arriver avant. Si c'est impossible (fenetre deja "
                + "passee a `departureTime`, trop de points...), l'arret est quand meme planifie mais `lateSeconds` > 0, "
                + "`feasible` = false et le score `hard` est negatif. Null = pas de borne haute (« pas d'importance »).",
                example = "2026-06-15T23:00:00", nullable = true)
        LocalDateTime timeWindowEnd) {

    public int resolvedDemand() {
        return demand != null ? demand : 0;
    }

    public int resolvedServiceDurationSeconds() {
        return serviceDurationSeconds != null ? Math.max(0, serviceDurationSeconds) : 0;
    }

    public boolean hasTimeWindow() {
        return timeWindowStart != null || timeWindowEnd != null;
    }

    @AssertTrue(message = "timeWindowStart doit etre anterieur ou egal a timeWindowEnd")
    @Schema(hidden = true)
    public boolean isTimeWindowValid() {
        return timeWindowStart == null || timeWindowEnd == null || !timeWindowStart.isAfter(timeWindowEnd);
    }
}
