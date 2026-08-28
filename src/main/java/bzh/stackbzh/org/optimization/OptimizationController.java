package bzh.stackbzh.org.optimization;

import bzh.stackbzh.org.optimization.dto.OptimizeRequest;
import bzh.stackbzh.org.optimization.dto.OptimizeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/optimization")
@Tag(name = "Optimisation", description = """
        Optimisation de tournees (VRP/TSP, avec fenetres horaires = VRPTW) via Timefold. Determine \
        l'ordre de passage optimal des points (et leur repartition entre vehicules) en minimisant le \
        temps de conduite (+ temps d'attente), sous contrainte de capacite et de fenetres horaires de \
        livraison. S'appuie sur le moteur de routing pour la matrice de temps : si le routing est \
        indisponible, renvoie 503.""")
public class OptimizationController {

    private final OptimizationService service;

    public OptimizationController(OptimizationService service) {
        this.service = service;
    }

    @PostMapping("/optimize")
    @Operation(summary = "Optimiser une tournee (ordre de passage, fenetres horaires, heure de depart)",
            description = """
                    Optimise l'ordre de passage d'une liste de points depuis/vers un depot commun.

                    Deroulement interne :
                    1. construction des points (depot + visites) ; les fenetres horaires sont converties en \
                    offsets (secondes) par rapport a `departureTime` ;
                    2. calcul de la matrice des temps reels via le routing local (GraphHopper) ;
                    3. resolution du VRP avec Timefold (duree allouee = `timefold.solver.termination.spent-limit`, \
                    1 s par defaut) : contraintes DURES = capacite vehicule et arrivee avant `timeWindowEnd` ; \
                    contraintes SOUPLES (minimisees) = temps de conduite total + temps d'attente aux fenetres ;
                    4. renvoi des tournees ordonnees par vehicule, avec les heures d'arrivee/debut de service/depart \
                    de chaque arret.

                    Cas d'usage typiques :
                    - **1 vehicule, capacite illimitee** : simple optimisation d'ordre (TSP). Omettre \
                    `vehicleCapacity` (= illimite, aucune contrainte de charge) et `demand` (= 0). \
                    L'ordre optimal de passage est `routes[0].stops[].visitId` dans l'ordre du tableau.
                    - **N vehicules + `vehicleCapacity` + `demand`** : repartition capacitaire (CVRP).
                    - **Fenetres horaires (VRPTW)** : ex. une pharmacie a livrer entre 21h et 23h -> \
                    `departureTime: "2026-06-15T20:00:00"` sur la requete et, sur la visite, \
                    `timeWindowStart: "2026-06-15T21:00:00"`, `timeWindowEnd: "2026-06-15T23:00:00"`. \
                    Les autres visites sans fenetre (= « pas d'importance ») sont placees librement autour.

                    **Heure de depart estimee (`departureTime`)** : origine des temps de la tournee. Toutes les \
                    heures de la reponse en decoulent et les fenetres horaires sont evaluees par rapport a elle. \
                    Omise = heure courante du serveur. A TOUJOURS renseigner quand des fenetres sont fournies.

                    **Semantique des fenetres horaires** (chaque borne est optionnelle ; aucune = pas de contrainte) :
                    - `timeWindowStart` : si le vehicule arrive avant, il **attend** sur place jusqu'a l'ouverture \
                    (`stops[].waitingSeconds`, `serviceStartTime` = ouverture). L'attente est minimisee (soft) mais \
                    n'est jamais une erreur.
                    - `timeWindowEnd` : heure LIMITE d'**arrivee** (contrainte dure). Le solveur reordonne les points \
                    pour la tenir. Si c'est impossible (fenetre deja passee, trop de points, attentes en chaine), la \
                    tournee est quand meme renvoyee (meilleure solution trouvee) avec `feasible=false`, \
                    `timeWindowViolations` > 0 et `stops[].lateSeconds` > 0 sur les arrets concernes ; le score \
                    `hard` est negatif. Aucune notification Discord n'est emise (resultat metier normal). Ce n'est PAS un 400 : le \
                    client doit lire `feasible` et decider (accepter le retard, changer `departureTime`, retirer un point).
                    - `timeWindowStart` > `timeWindowEnd` -> 400 (validation).

                    Semantique des autres valeurs par defaut : `vehicleCount` omis = 1 ; `vehicleCapacity` omis = \
                    illimite ; `demand` omis = 0 ; `serviceDurationSeconds` omis = 0. L'ordre des arrets retourne \
                    EST l'ordre de passage optimal.

                    Note : avec capacite illimitee et plusieurs vehicules, le solveur tend a n'en utiliser qu'un \
                    (chaque vehicule ajoute un aller-retour au depot), sauf si les fenetres horaires l'imposent.

                    La reponse est enrichie pour l'affichage : pour chaque segment (point precedent -> point), \
                    distance (m), duree (s) et **geometrie** ; pour chaque arret, distance/temps cumules, \
                    **`arrivalTime`, `serviceStartTime`, `departureTime`**, la fenetre demandee, \
                    `waitingSeconds` et `lateSeconds`. Par tournee : `departureTime`, `returnTime`, temps de \
                    conduite/service/attente. Chaque `routes[]` expose aussi **`geometry`/`geometryPolyline`** : \
                    la trace COMPLETE de la tournee (depot -> arrets -> depot), a utiliser pour afficher le trace \
                    global d'un seul trait (les polylignes par segment ne se concatenent pas). Mettre \
                    `geometryFormat=NONE` (ou `includeGeometry=false`) pour alleger la reponse.

                    Points non rattachables au reseau routier (tolerance) : avant l'optimisation, chaque point \
                    est teste contre le reseau routier. Une visite dont les coordonnees ne peuvent PAS etre \
                    rattachees a une route (en mer, hors zone OSM couverte, reseau deconnecte) ou dont la route \
                    la plus proche depasse le seuil `app.routing.max-snap-distance-meters` (1000 m par defaut) \
                    est **ECARTEE** de l'optimisation : elle n'apparait dans aucune tournee et est listee dans \
                    **`skippedVisits[]`** (avec `reason` = `UNROUTABLE` ou `TOO_FAR` et la distance de \
                    rattachement). Une seule visite invalide ne fait donc PLUS echouer toute la requete (plus de \
                    503 pour ce motif) : la tournee est calculee avec les points valides restants. Le client DOIT \
                    inspecter `skippedVisits` et signaler/corriger ces points. Si TOUTES les visites sont ecartees, \
                    la reponse est un 200 avec `routes` vide et tous les points dans `skippedVisits`. En revanche, \
                    si c'est le **depot** qui n'est pas rattachable, l'optimisation est impossible -> **400**.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tournee(s) optimisee(s). Verifier `feasible` (false = "
                    + "au moins une fenetre horaire ou capacite non tenable, details dans `stops[].lateSeconds`) "
                    + "et `skippedVisits` (points ecartes car non rattachables au reseau routier ou trop eloignes)."),
            @ApiResponse(responseCode = "400", description = "Depot manquant/non rattachable au reseau routier, "
                    + "liste de visites vide, ou fenetre horaire invalide (`timeWindowStart` > `timeWindowEnd`)",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "Routing indisponible (matrice non calculable)", content = @Content)
    })
    public OptimizeResponse optimize(@Valid @RequestBody OptimizeRequest request) {
        return service.optimize(request);
    }
}
