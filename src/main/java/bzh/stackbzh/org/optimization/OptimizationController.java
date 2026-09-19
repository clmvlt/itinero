package bzh.stackbzh.org.optimization;

import bzh.stackbzh.org.optimization.dto.DispatchRequest;
import bzh.stackbzh.org.optimization.dto.DispatchResponse;
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
        Optimisation de tournees via Timefold, avec fenetres horaires (VRPTW). Deux operations :
        - **`/optimize`** : ordre de passage optimal d'une liste de points (TSP/VRP) en minimisant le temps \
        total (conduite + attente). Avec plusieurs vehicules sans capacite, le solveur tend a n'en utiliser \
        qu'un seul.
        - **`/dispatch`** : repartition AUTOMATIQUE de N points (une centaine ou plus) en K tournees \
        EQUILIBREES en duree, chaque tournee couvrant une zone geographique coherente et etant ordonnee. \
        Ne repose pas sur la capacite ; temps de resolution choisi par requete.

        Les deux acceptent, EN OPTION et melangeables avec les points simples `visits`, des **missions \
        appairees `shipments`** (chargement a un endroit -> enlevement a un autre) : l'API garantit alors \
        que les deux arrets d'une mission sont servis par le MEME vehicule et que le chargement passe \
        AVANT l'enlevement. Une requete sans `shipments` se comporte exactement comme avant.

        Les deux s'appuient sur le moteur de routing pour la matrice de temps reels : si le routing est \
        indisponible, elles renvoient 503.""")
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
                    (`stops[].waitingSeconds`, `serviceStartTime` = ouverture). L'attente est minimisee (soft) et \
                    toleree jusqu'a **`maxWaitingSeconds`** (requete ; defaut serveur \
                    `app.optimization.max-waiting-seconds` = 3600 s = 1 h ; `0` = illimite). Au-dela, l'arret \
                    est considere comme NE CORRESPONDANT PAS au creneau (contrainte dure) : le solveur reordonne \
                    pour l'eviter et, si aucun ordre ne le permet, la tournee est renvoyee avec `feasible=false`, \
                    `stops[].timeWindowStatus=WAITING_TOO_LONG` et `stops[].excessiveWaitingSeconds` > 0 (les \
                    heures restent calculees comme si le vehicule attendait l'ouverture). Cela evite les \
                    tournees ou le vehicule attend des heures devant un client ferme.
                    - `timeWindowEnd` : heure LIMITE d'**arrivee** (contrainte dure). Le solveur reordonne les points \
                    pour la tenir. Si c'est impossible (fenetre deja passee, trop de points, attentes en chaine), la \
                    tournee est quand meme renvoyee (meilleure solution trouvee) avec `feasible=false`, \
                    `timeWindowViolations` > 0, `stops[].timeWindowStatus=LATE` et `stops[].lateSeconds` > 0 sur \
                    les arrets concernes ; le score `hard` est negatif. Aucune notification Discord n'est emise (resultat metier normal). Ce n'est PAS un 400 : le \
                    client doit lire `feasible` et decider (accepter le retard, changer `departureTime`, retirer un point).
                    - `timeWindowStart` > `timeWindowEnd` -> 400 (validation).

                    Chaque arret expose `timeWindowStatus` : `OK`, `LATE` ou `WAITING_TOO_LONG` ; tout statut \
                    autre que `OK` compte dans `timeWindowViolations` et rend `feasible=false`. La reponse rappelle \
                    la limite appliquee dans `maxWaitingSeconds` (null = pas de limite).

                    Semantique des autres valeurs par defaut : `vehicleCount` omis = 1 ; `vehicleCapacity` omis = \
                    illimite ; `demand` omis = 0 ; `serviceDurationSeconds` omis = 0 ; `maxWaitingSeconds` omis = \
                    defaut serveur (3600 s). L'ordre des arrets retourne \
                    EST l'ordre de passage optimal.

                    Note : avec capacite illimitee et plusieurs vehicules, le solveur tend a n'en utiliser qu'un \
                    (chaque vehicule ajoute un aller-retour au depot), sauf si les fenetres horaires l'imposent. \
                    Pour repartir N points en K tournees equilibrees en duree, utiliser **`/optimization/dispatch`**.

                    La reponse est enrichie pour l'affichage : pour chaque segment (point precedent -> point), \
                    distance (m), duree (s) et **geometrie** ; pour chaque arret, distance/temps cumules, \
                    **`arrivalTime`, `serviceStartTime`, `departureTime`**, la fenetre demandee, \
                    `waitingSeconds`, `excessiveWaitingSeconds`, `lateSeconds` et `timeWindowStatus`. Par tournee : `departureTime`, `returnTime`, temps de \
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
                    si c'est le **depot** qui n'est pas rattachable, l'optimisation est impossible -> **400**.

                    **Missions appairees (`shipments`) — OPTION.** A cote de `visits` (des points independants, \
                    que le solveur ordonne librement), la requete peut porter des `shipments` : une marchandise \
                    chargee a un endroit (`pickup`) puis enlevee/deposee a un autre (`delivery`). L'API ajoute \
                    alors deux contraintes DURES : les deux arrets sont servis par le **MEME vehicule**, et le \
                    **chargement passe AVANT l'enlevement**. Les deux listes se melangent librement ; une requete \
                    sans `shipments` se comporte a l'identique de l'existant.
                    - Chaque extremite est un `VisitDto` complet : elle a sa propre duree de service et sa propre \
                    fenetre horaire (on peut donc exiger un chargement le matin et une depose l'apres-midi).
                    - Une mission peut n'avoir qu'UNE extremite : `pickup` absent = marchandise chargee au depot \
                    au depart (livraison classique) ; `delivery` absent = marchandise ramenee au depot en fin de \
                    tournee (collecte). Le depot encadrant deja la tournee, aucun ordre n'est impose dans ce cas.
                    - Dans la reponse, chaque arret porte `shipmentId` et `stopType` (`PICKUP`/`DELIVERY`) pour \
                    recoller les deux bouts ; `pairingViolations` compte les missions cassees (0 attendu).
                    - **Points non rattachables** : une mission est ecartee EN ENTIER ou pas du tout. Si une seule \
                    extremite n'est pas rattachable, l'autre est ecartee avec `reason` = `PAIRED_POINT_SKIPPED` \
                    (on ne peut pas enlever une marchandise jamais chargee).
                    - **Temps de resolution** : des qu'il y a des missions, le probleme est plus difficile (l'etat \
                    initial ignore la precedence, le solveur doit d'abord la reparer). Le budget passe donc de la \
                    terminaison globale (1 s) a `maxSolvingSeconds`, sinon au defaut serveur \
                    `app.optimization.shipments.default-solving-seconds` (5 s), plafonne a 60 s. La requete HTTP \
                    dure alors au moins ce temps ; la valeur appliquee est renvoyee dans `solvingTimeSeconds`.
                    - `demand` garde sa semantique : une SOMME comparee a `vehicleCapacity` sur la tournee \
                    entiere, et non un suivi de la charge a bord. Le porter sur le `pickup` et laisser le \
                    `delivery` a 0, sinon la charge est comptee deux fois.
                    - Les identifiants doivent etre uniques dans la requete des qu'une mission est presente \
                    (l'appairage se fait par identifiant) -> sinon **400**.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tournee(s) optimisee(s). Verifier `feasible` (false = "
                    + "au moins une fenetre horaire (retard ou attente > `maxWaitingSeconds`), une capacite non "
                    + "tenable ou une mission appairee cassee ; details dans `stops[].timeWindowStatus`, "
                    + "`lateSeconds`, `excessiveWaitingSeconds` et `pairingViolations`) "
                    + "et `skippedVisits` (points ecartes car non rattachables au reseau routier, trop eloignes, "
                    + "ou parce que l'autre extremite de leur mission l'etait)."),
            @ApiResponse(responseCode = "400", description = "Depot manquant/non rattachable au reseau routier, "
                    + "requete sans aucun point (`visits` et `shipments` vides), mission sans `pickup` ni "
                    + "`delivery`, identifiant de point duplique (requete avec missions), fenetre horaire "
                    + "invalide (`timeWindowStart` > `timeWindowEnd`), `maxWaitingSeconds` negatif ou "
                    + "`maxSolvingSeconds` < 1",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "Routing indisponible (matrice non calculable)", content = @Content)
    })
    public OptimizeResponse optimize(@Valid @RequestBody OptimizeRequest request) {
        return service.optimize(request);
    }

    @PostMapping("/dispatch")
    @Operation(summary = "Repartir N points en K tournees equilibrees en duree (repartition automatique)",
            description = """
                    Recoit un depot, un NOMBRE de tournees `vehicleCount` (ex : 5) et une liste de points (ex : 100) \
                    et repartit elle-meme les points entre les tournees, puis ordonne chaque tournee. Aucune \
                    capacite ni aucun `demand` n'est necessaire : la repartition se fait sur les DUREES.

                    **Difference avec `/optimize`** : `/optimize` minimise le temps total et, avec plusieurs vehicules \
                    sans capacite, finit mathematiquement par tout mettre sur un seul vehicule (chaque vehicule \
                    supplementaire ajoute un aller-retour au depot). `/dispatch` change l'objectif : il minimise la \
                    **somme des CARRES des durees de tournee** (duree = conduite + service + attentes). Cette somme vaut \
                    (temps total)^2 / K + K x variance : la minimiser reduit a la fois le temps global ET l'ecart entre \
                    vehicules, sans reglage. Resultat : les K vehicules sont utilises, chacun couvre une zone \
                    geographique coherente (les points proches finissent dans la meme tournee) et les durees sont \
                    proches sans etre strictement egales (un desequilibre est accepte s'il fait gagner du temps \
                    globalement, ou si les fenetres horaires / la geographie l'imposent). Avec `vehicleCount = 1`, \
                    l'optimum est le meme que `/optimize`.

                    Deroulement interne :
                    1. verification du depot (non rattachable -> 400) et de chaque point (non rattachable -> ecarte \
                    dans `skippedVisits`, cf. plus bas) ; fenetres horaires converties en offsets par rapport a \
                    `departureTime` ;
                    2. matrice des temps reels entre tous les points (routing local GraphHopper, (N+1)^2 routages : \
                    ~10 000 pour 100 points, quelques secondes) ;
                    3. resolution Timefold pendant **`maxSolvingSeconds`** (defaut serveur 10 s, plafond 60 s) : \
                    contraintes DURES = arrivee avant `timeWindowEnd`, attente <= `maxWaitingSeconds`, capacite si \
                    `vehicleCapacity` est fourni ; contrainte SOUPLE dominante = somme des carres des durees de tournee \
                    (+ conduite et attente en departage) ;
                    4. reponse : une tournee ordonnee par vehicule (memes champs que `/optimize` : segments, cumuls, \
                    heures d'arrivee/debut de service/depart, statut de fenetre, geometrie complete) + indicateurs \
                    d'equilibre (`balance`).

                    **Temps de reponse** : la requete HTTP dure au moins `maxSolvingSeconds` + le calcul de la matrice. \
                    C'est voulu : plus le budget est long, meilleure est la repartition (100 points / 5 tournees : \
                    ~10 s correct, 20-30 s tres bon). Prevoir un timeout client d'au moins 90 s. Une valeur \
                    superieure au plafond serveur est ramenee silencieusement au plafond ; le budget reellement \
                    applique est renvoye dans `solvingTimeSeconds`. Chaque resolution occupe un thread du solveur \
                    pendant toute sa duree : les appels simultanes au-dela du parallelisme configure sont mis en \
                    file d'attente.

                    **Fenetres horaires, attente max, heure de depart** : memes regles et memes champs que \
                    `/optimize` (`departureTime` = origine des temps, `timeWindowStart` -> attente, `timeWindowEnd` \
                    -> heure limite d'arrivee (dure), `maxWaitingSeconds` -> attente toleree). Une fenetre intenable \
                    n'est pas un 400 : la meilleure repartition est renvoyee avec `feasible=false`, \
                    `timeWindowViolations` > 0 et `stops[].timeWindowStatus` = `LATE` ou `WAITING_TOO_LONG`. \
                    Toutes les tournees partent du depot a `departureTime`.

                    **Lecture de la reponse** : `routes` contient exactement `vehicleCount` entrees (`vehicle-0` .. \
                    `vehicle-<K-1>`), chacune deja ordonnee (`stops[]` = ordre de passage) avec sa duree totale \
                    `durationSeconds`. `usedVehicleCount` < `vehicleCount` seulement s'il y a moins de points valides \
                    que de vehicules (tournees vides). `balance` donne la tournee la plus longue / la plus courte, la \
                    moyenne et l'ecart entre tournees utilisees. `totalDurationSeconds` = somme des durees. Le champ \
                    `score` n'est comparable qu'entre deux resolutions du meme probleme.

                    **Capacite (facultative)** : si `vehicleCapacity` est fourni, la somme des `demand` d'une tournee \
                    ne peut pas le depasser (contrainte dure, `feasible=false` si intenable). Sans `vehicleCapacity`, \
                    `demand` est ignore.

                    **Points non rattachables** : meme tolerance que `/optimize`. Une visite en mer / hors zone / \
                    a plus de `app.routing.max-snap-distance-meters` de toute route est ECARTEE et listee dans \
                    `skippedVisits[]` (`reason` = `UNROUTABLE` ou `TOO_FAR`) ; la repartition se fait sur les points \
                    restants. Toutes ecartees -> 200 avec `routes` vide et `usedVehicleCount = 0`. Depot non \
                    rattachable -> 400.

                    **Geometrie** : `geometryFormat` = `POINTS` (defaut), `POLYLINE` (recommande : une centaine de \
                    segments) ou `NONE`. Par tournee, `geometry`/`geometryPolyline` = trace COMPLETE depot -> arrets \
                    -> depot (polyline remplie sauf en NONE) ; par arret, `legFromPrevious` = segment individuel.

                    **Missions appairees (`shipments`) — OPTION.** A cote de `visits`, la requete peut porter des \
                    missions « chargement -> enlevement » (memes champs et memes regles que sur `/optimize`). Une \
                    mission n'est JAMAIS coupee entre deux tournees : ses deux arrets partent dans la meme, le \
                    chargement d'abord (contraintes DURES). L'equilibrage porte donc sur des missions entieres, ce \
                    qui reduit un peu la finesse de la repartition : a nombre de points egal, l'ecart \
                    `balance.spreadSeconds` est generalement plus grand qu'avec des points independants, et un \
                    budget `maxSolvingSeconds` plus large est recommande (le solveur doit d'abord reparer la \
                    precedence avant d'optimiser). Chaque arret de la reponse porte `shipmentId` et `stopType` \
                    (`PICKUP`/`DELIVERY`) ; `pairingViolations` compte les missions cassees (0 attendu). Si une \
                    extremite n'est pas rattachable au reseau, la mission entiere est ecartee (`reason` = \
                    `PAIRED_POINT_SKIPPED`). Une requete sans `shipments` se comporte a l'identique de l'existant.

                    Exemple minimal : `{ "depot": {"lat": 48.1173, "lon": -1.6778}, "vehicleCount": 5, \
                    "maxSolvingSeconds": 20, "geometryFormat": "POLYLINE", "visits": [ {"id": "A", "lat": 48.12, \
                    "lon": -1.70}, ... 100 points ... ] }`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Repartition calculee : `routes` (une tournee ordonnee "
                    + "par vehicule, `vehicleCount` entrees), `balance` (equilibre des durees), `solvingTimeSeconds` "
                    + "(budget applique). Verifier `feasible` (false = fenetre horaire, attente max, capacite "
                    + "intenable ou mission appairee cassee ; details dans `stops[].timeWindowStatus` et "
                    + "`pairingViolations`) et `skippedVisits` (points ecartes)."),
            @ApiResponse(responseCode = "400", description = "Depot manquant ou non rattachable au reseau routier, "
                    + "`vehicleCount` absent ou < 1, requete sans aucun point (`visits` et `shipments` vides), "
                    + "mission sans `pickup` ni `delivery`, identifiant de point duplique (requete avec missions), "
                    + "coordonnee manquante, fenetre horaire invalide (`timeWindowStart` > `timeWindowEnd`), "
                    + "`maxWaitingSeconds` negatif ou `maxSolvingSeconds` < 1", content = @Content),
            @ApiResponse(responseCode = "503", description = "Routing indisponible (matrice non calculable : donnees "
                    + "absentes ou graphe en cours de (re)construction)", content = @Content)
    })
    public DispatchResponse dispatch(@Valid @RequestBody DispatchRequest request) {
        return service.dispatch(request);
    }
}
