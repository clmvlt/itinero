# Prompt — système complet de la route `POST /optimization/dispatch`

> À copier tel quel et à donner à une IA (ou à lire par un développeur) qui doit **intégrer, tester,
> maintenir ou faire évoluer** la route de répartition automatique de tournées de l'API spring-org.
> Le prompt général de toute l'API reste servi sur `/prompt.html` ; celui-ci se concentre sur `/dispatch`
> et décrit **tout le système** : le besoin, le raisonnement mathématique, la mécanique interne, le contrat
> HTTP exact, les règles métier, les réglages serveur, les performances et les limites.

---

Tu travailles avec l'API HTTP « spring-org » (Spring Boot, 100 % locale : GraphHopper pour le routing, Timefold
pour l'optimisation, aucune API externe à l'exécution). Base de production : `https://ors.stack.bzh`
(en dev : `http://localhost:8080`). Spec OpenAPI = source de vérité du contrat : `GET /v3/api-docs`,
Swagger UI : `/swagger-ui.html`. Respecte STRICTEMENT les noms de champs, types, valeurs par défaut et codes
de réponse ci-dessous. N'invente aucun champ.

## 1. Ce que fait la route

`POST /optimization/dispatch` reçoit **un dépôt**, **un nombre de tournées** `vehicleCount` (ex : 5) et **une
liste de points** `visits` (typiquement 20 à 300, ex : 100). Elle **répartit elle-même** les points entre les
tournées, **ordonne** chaque tournée, et renvoie les K tournées avec heures, cumuls, géométrie et des
indicateurs d'équilibre. La répartition est **optimisée en temps pour tout le monde** : les durées de tournée
(conduite + service + attente) sont proches entre chauffeurs, chaque tournée couvre une zone géographique
cohérente, et le temps global reste minimal. **Aucune capacité ni aucun `demand` n'est nécessaire.**

## 2. Pourquoi une route dédiée (et pas `/optimize` avec `vehicleCount: 5`)

`POST /optimization/optimize` minimise le **temps total** (conduite + attente). Avec un dépôt commun et sans
capacité, fusionner deux tournées coûte toujours moins qu'un aller-retour dépôt supplémentaire (inégalité
triangulaire, exacte pour des plus courts chemins). Le solveur met donc **tout sur un seul véhicule** et laisse
les autres vides (mesuré : 100 points / 5 véhicules → 100 / 0 / 0 / 0 / 0). Forcer un découpage par
`vehicleCapacity` donne un découpage capacitaire, pas des tournées logiques.

`/dispatch` change **l'objectif du solveur** : minimiser la **somme des carrés des durées de tournée**.
Somme des carrés = (temps total)² / K + K × variance des durées. Minimiser ce seul terme réduit **à la fois**
le temps global **et** l'écart entre véhicules, sans poids à régler ni contrainte d'égalité stricte. Avec
`vehicleCount = 1`, l'optimum est identique à `/optimize`. Résultat mesuré sur matrice synthétique :
100 points en 5 zones / 5 véhicules → 20 / 20 / 21 / 19 / 20 visites, un véhicule par zone.

## 3. Fonctionnement interne, étape par étape

1. **Validation du corps** (Bean Validation) : `depot` requis, `vehicleCount` requis ≥ 1, `visits` non vide,
   `lat`/`lon` requis par visite, `timeWindowStart ≤ timeWindowEnd`, `maxWaitingSeconds ≥ 0`,
   `maxSolvingSeconds ≥ 1`. Sinon **400** (`application/problem+json`).
2. **Budget de résolution** : `maxSolvingSeconds` de la requête, sinon défaut serveur
   `app.optimization.dispatch.default-solving-seconds` (10 s) ; plafonné **silencieusement** à
   `app.optimization.dispatch.max-solving-seconds` (60 s). La valeur appliquée est renvoyée dans
   `solvingTimeSeconds`.
3. **Rattachement au réseau routier** : le dépôt est testé (non rattachable → **400**, notification Discord) ;
   chaque visite est testée. Une visite en mer, hors zone OSM, sur un réseau déconnecté (`UNROUTABLE`) ou à plus
   de `app.routing.max-snap-distance-meters` (1000 m) de toute route (`TOO_FAR`) est **écartée** : elle n'entre
   dans aucune tournée et figure dans `skippedVisits[]` (notification Discord). Si toutes sont écartées → **200**
   avec `routes = []`, `usedVehicleCount = 0`.
4. **Origine des temps** : `departureTime` (ou l'heure courante du serveur), tronquée à la seconde. Toutes les
   fenêtres horaires sont converties en offsets en secondes par rapport à cet instant. Toutes les tournées
   partent du dépôt à `departureTime`.
5. **Matrice des temps réels** : (N+1)×(N+1) routages GraphHopper (Contraction Hierarchies, profil `car`),
   ~10 000 routages pour 100 points, quelques secondes. Routing indisponible (données absentes ou graphe en
   reconstruction) → **503**.
6. **Modèle Timefold** : K entités `Vehicle` (liste ordonnée de visites, `balanceDuration = true`) et N entités
   `Visit` (variables fantômes : véhicule, précédent/suivant, heure d'arrivée en cascade). Contraintes :
   - **dures** : arrivée après `timeWindowEnd` (retard), attente avant `timeWindowStart` au-delà de
     `maxWaitingSeconds`, capacité dépassée (seulement si `vehicleCapacity` est fourni) ;
   - **souple dominante** : `durée_tournée²` par véhicule, où durée = conduite + service + attentes, recalculée
     par simulation de la liste de visites ;
   - **souples de départage** : conduite totale, attente totale (négligeables devant le carré).
7. **Résolution** pendant exactement le budget (construction heuristique puis recherche locale ; le solveur
   ne s'arrête pas plus tôt). La configuration globale du solveur (1 s pour `/optimize`) n'est pas modifiée :
   la limite est surchargée **pour ce job uniquement**.
8. **Construction de la réponse** : chaque segment (dépôt → arrêt → … → dépôt) est re-routé pour obtenir
   distance, durée et géométrie réelles ; les heures sont recalculées avec la même règle que le solveur
   (attente si en avance sur `timeWindowStart`, retard si après `timeWindowEnd`) ; indicateurs d'équilibre
   calculés sur les tournées **utilisées** (≥ 1 arrêt).

## 4. Contrat de requête (`DispatchRequest`, JSON)

| Champ | Type | Requis | Défaut | Rôle |
|---|---|---|---|---|
| `depot` | `{lat, lon}` | oui | — | Départ **et** arrivée commun à toutes les tournées. Non rattachable → 400. |
| `vehicleCount` | integer ≥ 1 | **oui** | — | Nombre de tournées à produire (= nombre de véhicules). Absent ou < 1 → 400. |
| `vehicleCapacity` | integer \| null | non | null = illimité | Facultatif, inutile pour la répartition. Si fourni : contrainte **dure**, la somme des `demand` d'une tournée ne peut pas le dépasser. |
| `departureTime` | LocalDateTime ISO-8601 (sans fuseau) \| null | non | heure courante serveur | Origine des temps. À renseigner dès qu'une visite a une fenêtre horaire. |
| `maxWaitingSeconds` | integer ≥ 0 \| null | non | 3600 (serveur) | Attente max tolérée devant une fenêtre pas encore ouverte. `0` = illimitée. Au-delà → violation dure. |
| `maxSolvingSeconds` | integer ≥ 1 \| null | non | 10 (serveur) | Budget de résolution. Plafonné à 60 (serveur), silencieusement. |
| `geometryFormat` | `POINTS` \| `POLYLINE` \| `NONE` | non | `POINTS` | `POLYLINE` recommandé (≈ 100 segments). Insensible à la casse. |
| `visits` | `VisitDto[]` non vide | **oui** | — | Points à répartir. |

`VisitDto` (identique à `/optimize`) : `id` (string, auto `v<index>` si absent), `name` (string), `lat`,
`lon` (requis), `demand` (integer, défaut 0, ignoré sans `vehicleCapacity`), `serviceDurationSeconds`
(integer, défaut 0, décale les heures suivantes), `timeWindowStart` / `timeWindowEnd` (LocalDateTime,
optionnels et indépendants ; `start > end` → 400).

Il n'y a **pas** de champ `includeGeometry` sur cette route.

## 5. Contrat de réponse 200 (`DispatchResponse`, JSON)

| Champ | Type | Sens |
|---|---|---|
| `score` | string | `"<dur>hard/<soft>soft"`. `0hard` = contraintes dures respectées. Le `soft` (somme des carrés, en s²) n'est comparable qu'entre deux résolutions du **même** problème. |
| `feasible` | boolean | `true` = aucune fenêtre en retard, aucune attente > `maxWaitingSeconds`, aucune capacité dépassée. `false` = tournées quand même renvoyées (meilleure solution trouvée). |
| `timeWindowViolations` | integer | Nombre d'arrêts avec `timeWindowStatus ≠ OK`. |
| `maxWaitingSeconds` | integer \| null | Limite d'attente appliquée ; `null` = aucune. |
| `solvingTimeSeconds` | integer | Budget de résolution réellement appliqué. |
| `vehicleCount` | integer | Nombre de tournées demandé = `routes.length`. |
| `usedVehicleCount` | integer | Tournées avec ≥ 1 arrêt. Inférieur à `vehicleCount` seulement s'il y a moins de points valides que de véhicules. |
| `totalDrivingTimeSeconds` | integer | Conduite cumulée, toutes tournées. |
| `totalDistanceMeters` | number | Distance cumulée, toutes tournées. |
| `totalDurationSeconds` | integer | Somme des `routes[].durationSeconds`. |
| `balance` | objet | Sur les tournées utilisées, en secondes : `longestRouteSeconds`, `shortestRouteSeconds`, `averageRouteSeconds` (arrondie), `spreadSeconds` (= longest − shortest ; petit = charge équitable). Zéros si aucune tournée utilisée. |
| `routes` | `RouteDto[]` | **Exactement `vehicleCount` entrées**, `vehicle-0` … `vehicle-<K-1>`, chacune déjà ordonnée. |
| `skippedVisits` | `SkippedVisitDto[]` | Visites écartées : `visitId`, `name`, `lat`, `lon`, `reason` (`UNROUTABLE` \| `TOO_FAR`), `snapDistanceMeters` (renseigné pour `TOO_FAR`). |

`RouteDto` (identique à `/optimize`) : `vehicleId`, `departureTime`, `returnTime`, `drivingTimeSeconds`,
`serviceTimeSeconds`, `waitingTimeSeconds`, **`durationSeconds`** (= conduite + service + attente = `returnTime`
− `departureTime`, 0 si vide), `distanceMeters`, `totalDemand`, `stops[]` (**ordre de passage**), `returnLeg`,
`geometry` (`[[lat,lon],…]`, non nul seulement en `POINTS`), `geometryPolyline` (trace **complète** de la
tournée, polyligne Google/OSRM précision 5, non nulle en `POINTS` et `POLYLINE`, nulle en `NONE`).

`StopDto` : `visitId`, `name`, `lat`, `lon`, `legFromPrevious` (`distanceMeters`, `durationSeconds`,
`geometry` si `POINTS`, `geometryPolyline` si `POLYLINE`), `cumulativeDistanceMeters`,
`cumulativeDrivingSeconds`, `arrivalTime`, `serviceStartTime`, `departureTime`, `timeWindowStart`,
`timeWindowEnd`, `waitingSeconds`, `excessiveWaitingSeconds`, `lateSeconds`, `timeWindowStatus`
(`OK` \| `LATE` \| `WAITING_TOO_LONG`), `demand`.

## 6. Règles métier et cas limites

- **Équilibre ≠ égalité** : un écart entre tournées est normal quand il fait gagner du temps globalement, ou
  quand les fenêtres horaires / la géographie l'imposent (un chauffeur bloqué par un créneau reçoit moins
  d'autres arrêts). Lire `balance.spreadSeconds` pour juger.
- **Fenêtres horaires** : mêmes règles que `/optimize`. Arrivée avant `timeWindowStart` → attente sur place
  (`waitingSeconds`, tolérée jusqu'à `maxWaitingSeconds`, au-delà `WAITING_TOO_LONG`). Arrivée après
  `timeWindowEnd` → `LATE`. Une fenêtre intenable n'est **pas** un 400 : `feasible = false`, la meilleure
  répartition est renvoyée, à toi de décider (retirer le point, changer `departureTime`, relancer avec un
  budget plus long ou `maxWaitingSeconds: 0`). Aucune notification Discord dans ce cas (résultat métier).
- **Plus de véhicules que de points valides** : les véhicules excédentaires ont `stops = []`,
  `durationSeconds = 0`, et n'entrent pas dans `balance`.
- **Capacité** : sans `vehicleCapacity`, `demand` est ignoré. Avec, la contrainte est dure : si intenable,
  `feasible = false` et le `hard` du score est négatif.
- **Dépendance au routing** : si `GET /routing/status` renvoie `ready: false`, la route renvoie 503.
- **Concurrence** : chaque résolution occupe un thread du solveur pendant tout le budget ; les appels
  simultanés au-delà du parallélisme configuré sont mis en file d'attente (réponse plus tardive, pas d'erreur).

## 7. Codes de réponse

| Code | Quand | Corps |
|---|---|---|
| 200 | Répartition calculée (vérifier `feasible` et `skippedVisits`). | `DispatchResponse` |
| 400 | Dépôt manquant ou non rattachable ; `vehicleCount` absent ou < 1 ; `visits` vide ; coordonnée manquante ; `timeWindowStart > timeWindowEnd` ; `maxWaitingSeconds < 0` ; `maxSolvingSeconds < 1` ; JSON illisible. | `application/problem+json` (`title`, `detail`, `status`) |
| 503 | Routing indisponible (données absentes ou graphe en (re)construction). | `application/problem+json` |
| 500 | Erreur interne inattendue (notifiée sur Discord). | `application/problem+json` |

## 8. Performance et réglages serveur

- Durée d'une requête ≈ matrice (quelques secondes pour 100 points) + `solvingTimeSeconds` + re-routage des
  segments (rapide). **Prévoir un timeout HTTP client ≥ 90 s** et un état « calcul en cours » côté UI.
- Qualité selon le budget (100 points / 5 tournées, matrice synthétique) : 1 s déjà correct ; environ +2 % de
  temps total gagné à 5 s ; environ +4,5 % à 20 s. Recommandation : 10 s par défaut, 20 à 30 s pour un
  résultat de production.
- Réglages (`application.yml`, surchargeables par variables d'environnement) :
  `app.optimization.dispatch.default-solving-seconds` (`APP_DISPATCH_SOLVING_SECONDS`, 10),
  `app.optimization.dispatch.max-solving-seconds` (`APP_DISPATCH_MAX_SOLVING_SECONDS`, 60),
  `app.optimization.max-waiting-seconds` (`APP_MAX_WAITING_SECONDS`, 3600),
  `app.routing.max-snap-distance-meters` (`APP_MAX_SNAP_DISTANCE`, 1000). La limite globale
  `timefold.solver.termination.spent-limit` (1 s) ne concerne que `/optimize`.

## 9. Exemples

Requête (100 points, 5 tournées, 20 s, polylignes) :

```json
{
  "depot": { "lat": 48.1173, "lon": -1.6778 },
  "vehicleCount": 5,
  "departureTime": "2026-06-15T08:00:00",
  "maxSolvingSeconds": 20,
  "geometryFormat": "POLYLINE",
  "visits": [
    { "id": "A", "name": "Client A", "lat": 48.12, "lon": -1.70, "serviceDurationSeconds": 300 },
    { "id": "B", "name": "Pharmacie", "lat": 48.09, "lon": -1.65, "serviceDurationSeconds": 600,
      "timeWindowStart": "2026-06-15T09:00:00", "timeWindowEnd": "2026-06-15T11:00:00" }
  ]
}
```

Réponse (abrégée) :

```json
{
  "score": "0hard/-84567321soft",
  "feasible": true,
  "timeWindowViolations": 0,
  "maxWaitingSeconds": 3600,
  "solvingTimeSeconds": 20,
  "vehicleCount": 5,
  "usedVehicleCount": 5,
  "totalDrivingTimeSeconds": 21330,
  "totalDistanceMeters": 412800.0,
  "totalDurationSeconds": 39330,
  "balance": { "longestRouteSeconds": 8340, "shortestRouteSeconds": 7410, "averageRouteSeconds": 7866, "spreadSeconds": 930 },
  "routes": [
    { "vehicleId": "vehicle-0", "departureTime": "2026-06-15T08:00:00", "returnTime": "2026-06-15T10:19:00",
      "drivingTimeSeconds": 5340, "serviceTimeSeconds": 3000, "waitingTimeSeconds": 0, "durationSeconds": 8340,
      "distanceMeters": 98200.0, "totalDemand": 0,
      "stops": [ { "visitId": "B", "arrivalTime": "2026-06-15T09:02:00", "timeWindowStatus": "OK", "..." : "..." } ],
      "returnLeg": { "distanceMeters": 12100.0, "durationSeconds": 900, "geometry": null, "geometryPolyline": "…" },
      "geometry": null, "geometryPolyline": "ydlrHnwfA~A_@dGsT…" }
  ],
  "skippedVisits": []
}
```

Lecture côté client : pour chaque `i`, `routes[i].stops` = ordre de passage du chauffeur `i`,
`routes[i].durationSeconds` = sa journée, `routes[i].geometryPolyline` = son tracé complet (décoder avec
`@mapbox/polyline`, précision 5, ordre lat/lon) ; `balance.spreadSeconds` = écart entre le chauffeur le plus
chargé et le moins chargé.

## 10. Consignes d'intégration

- Utilise `/dispatch` (et non `/optimize` avec `vehicleCount > 1`) dès qu'il faut répartir des points entre
  plusieurs chauffeurs.
- Inspecte TOUJOURS `skippedVisits` et `feasible` avant d'afficher les tournées ; signale les points écartés
  et les arrêts `LATE` / `WAITING_TOO_LONG`.
- Renseigne `departureTime` dès qu'une visite a une fenêtre horaire.
- Demande `geometryFormat: "POLYLINE"` (ou `NONE` pour un simple tableau) : 100 points = 100 segments.
- Ne compare jamais `score` entre deux requêtes différentes ; compare `totalDurationSeconds` et `balance`.
- Avant d'appeler, `GET /routing/status` doit renvoyer `ready: true`.

## 11. Limites connues

- Un seul dépôt commun, un seul profil (`car`), pas de pauses conducteur, pas d'heure de départ par véhicule.
- Fenêtres horaires « dures » uniquement (pas de pénalité progressive au retard).
- Au-delà de ~300 points, la qualité par seconde de budget baisse (pas de *nearby selection* activée) et la
  requête HTTP devient longue : prévoir un mode asynchrone si ce cas apparaît.
- Le résultat n'est pas strictement déterministe d'un appel à l'autre (recherche locale bornée dans le temps).
