package bzh.stackbzh.org.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Resultat d'une repartition : K tournees equilibrees en duree (une par vehicule), chacune "
        + "ordonnee et enrichie comme sur `/optimize` (heures, cumuls, geometrie), plus des indicateurs "
        + "d'equilibre entre tournees.")
public record DispatchResponse(
        @Schema(description = "Score Timefold (`<dur>hard/<soft>soft`). `0hard` = toutes les contraintes dures sont "
                + "respectees (fenetres horaires, attente max, capacite si fournie). Le `soft` est domine par la "
                + "somme des CARRES des durees de tournee (s^2) + conduite + attente : il n'a de sens que pour "
                + "comparer deux resolutions du MEME probleme, pas entre requetes.",
                example = "0hard/-84567321soft")
        String score,
        @Schema(description = "true si TOUTES les contraintes dures sont respectees : aucun arret en retard sur sa "
                + "fenetre, aucune attente au-dela de `maxWaitingSeconds`, aucune capacite depassee et aucune "
                + "mission appairee cassee. false = les tournees sont quand meme renvoyees (meilleure solution "
                + "trouvee) : inspecter `timeWindowViolations`, `pairingViolations` et "
                + "`stops[].timeWindowStatus`.", example = "true")
        boolean feasible,
        @Schema(description = "Nombre d'arrets dont la fenetre horaire n'est PAS respectee (`LATE` ou "
                + "`WAITING_TOO_LONG`). 0 si toutes les fenetres sont tenues ou si aucune n'a ete fournie.",
                example = "0")
        int timeWindowViolations,
        @Schema(description = "Nombre de MISSIONS appairees (`shipments`) dont la regle n'est pas tenue : les deux "
                + "arrets repartis dans des tournees differentes, ou l'enlevement place avant son chargement. "
                + "0 = toutes les missions sont intactes (et toujours 0 sans `shipments` dans la requete).",
                example = "0")
        int pairingViolations,
        @Schema(description = "Attente maximale toleree devant une fenetre horaire effectivement appliquee, en "
                + "secondes. null = pas de limite (desactive par `0`).", example = "3600", nullable = true)
        Integer maxWaitingSeconds,
        @Schema(description = "Temps de resolution effectivement alloue au solveur, en secondes (valeur de la "
                + "requete plafonnee par le serveur, ou defaut serveur).", example = "20")
        int solvingTimeSeconds,
        @Schema(description = "Nombre de tournees demande (`vehicleCount` de la requete) = taille de `routes`.",
                example = "5")
        int vehicleCount,
        @Schema(description = "Nombre de tournees contenant au moins un arret. Inferieur a `vehicleCount` "
                + "seulement s'il y a moins de points valides que de vehicules.", example = "5")
        int usedVehicleCount,
        @Schema(description = "Temps de conduite total cumule sur toutes les tournees, en secondes.", example = "21330")
        long totalDrivingTimeSeconds,
        @Schema(description = "Distance totale parcourue par toutes les tournees, en metres.", example = "412800.0")
        double totalDistanceMeters,
        @Schema(description = "Somme des durees de tournee (`routes[].durationSeconds` : conduite + service + "
                + "attente), en secondes.", example = "39330")
        long totalDurationSeconds,
        @Schema(description = "Indicateurs d'equilibre entre les tournees UTILISEES (au moins un arret).")
        BalanceDto balance,
        @Schema(description = "Une entree par vehicule, dans l'ordre `vehicle-0` .. `vehicle-<K-1>`. Chaque "
                + "tournee est deja ordonnee (ordre optimal de passage) et couvre une zone geographique "
                + "coherente. Une tournee peut etre vide (aucun arret) s'il y a plus de vehicules que de points.")
        List<OptimizeResponse.RouteDto> routes,
        @Schema(description = "Visites EXCLUES car non rattachables au reseau routier (`UNROUTABLE`), trop "
                + "eloignees de toute route (`TOO_FAR`), ou parce que l'autre extremite de leur mission l'etait "
                + "(`PAIRED_POINT_SKIPPED` : une mission est ecartee en entier ou pas du tout). Elles ne figurent "
                + "dans AUCUNE tournee. Le client DOIT verifier ce tableau.")
        List<OptimizeResponse.SkippedVisitDto> skippedVisits) {

    @Schema(description = "Equilibre des durees entre les tournees utilisees. Toutes les valeurs sont en secondes et "
            + "portent sur `routes[].durationSeconds` (conduite + service + attente). Zeros si aucune tournee "
            + "n'a d'arret.")
    public record BalanceDto(
            @Schema(description = "Duree de la tournee la plus longue.", example = "8340")
            long longestRouteSeconds,
            @Schema(description = "Duree de la tournee la plus courte (parmi celles qui ont au moins un arret).",
                    example = "7410")
            long shortestRouteSeconds,
            @Schema(description = "Duree moyenne des tournees utilisees (arrondie).", example = "7866")
            long averageRouteSeconds,
            @Schema(description = "Ecart entre la plus longue et la plus courte (`longest - shortest`). Plus il est "
                    + "petit, plus la charge est equitable. Un ecart notable est normal quand les fenetres horaires "
                    + "ou la geographie l'imposent : l'objectif reste le temps global, pas l'egalite stricte.",
                    example = "930")
            long spreadSeconds) {
    }
}
