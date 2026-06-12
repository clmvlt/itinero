# CLAUDE.md — Guide de l'API `spring-org`

> Ce fichier est lu à **chaque prompt**. Il doit suffire, à lui seul, à comprendre
> l'API, son architecture, ses pièges et ses conventions. **Garde-le à jour.**

---

## ⚠️ RÈGLE ABSOLUE : documentation Swagger/OpenAPI

**À chaque ajout ou modification d'un endpoint, d'un DTO, d'un champ, d'un code de
réponse ou d'un comportement, tu DOIS mettre à jour la documentation OpenAPI de
façon INTÉGRALE et TRÈS DÉTAILLÉE**, pour qu'une IA (ou un humain) la comprenne
sans lire le code source. Concrètement, à chaque changement :

1. **Contrôleurs** : `@Tag` (description du module), `@Operation` (summary + description
   détaillée : ce que ça fait, comment, cas d'usage, effets de bord), `@ApiResponses`
   (lister **tous** les codes : 200, 400, 503...).
2. **DTOs** : `@Schema` sur la classe **et sur chaque champ** — `description`,
   `example` réaliste, `requiredMode`, `defaultValue`, `nullable` si pertinent.
3. **Métadonnées globales** : mettre à jour `OpenApiConfig` (description générale,
   version) si le périmètre change.
4. **Vérifier** : `GET /v3/api-docs` (HTTP 200) et `GET /swagger-ui.html` après build.

Une doc partielle ou un exemple manquant = travail non terminé. La doc OpenAPI est
le **contrat** de l'API et la première chose que lit une IA. Sois exhaustif.

- Swagger UI : http://localhost:8080/swagger-ui.html
- Spec JSON : http://localhost:8080/v3/api-docs

---

## 1. Objectif

API Spring Boot **100 % locale** (aucune dépendance à une API externe à
l'exécution). Remplace l'usage en ligne de commande de l'outil **VROOM**. Trois
briques indépendantes :

| Brique | Package | Techno | Rôle |
|---|---|---|---|
| **Routing** | `routing` | GraphHopper 11 (embarqué) | itinéraires + matrices de temps depuis un extrait OSM France |
| **Optimisation** | `optimization` | Timefold 1.33 | tournées VRP/TSP : ordre de passage optimal de N points |
| **Geocoding** | `geocoding` | Lucene 9 + BAN | recherche/autocomplétion d'adresse |
| Données (transverse) | `data` | HttpClient + cron | téléchargement & mise à jour auto des fichiers |

## 2. Architecture & flux

```
                         ┌────────────── API Spring Boot (un seul .jar) ──────────────┐
GET /geocoding/search │  geocoding.AddressSearchService  ── index Lucene (BAN CSV) │
                         │                                                            │
POST /routing/route   │  routing.RoutingEngine ── graphe GraphHopper (OSM pbf)     │
POST /routing/matrix  │  routing.MatrixService ── N×N routages pair-à-pair         │
                         │                                                            │
POST /optimization    │  optimization.OptimizationService :                        │
   /optimize              │    1) build points  2) MatrixService (temps réels)         │
                         │    3) Timefold SolverManager  4) tournées ordonnées        │
                         └────────────────────────────────────────────────────────────┘
   ▲ aucune dépendance réseau à l'exécution (données locales)
```

- **L'optimisation dépend du routing** (elle calcule sa matrice via `MatrixService`).
  Si le routing est KO → `/optimize` renvoie 503.
- Chaque brique est **tolérante aux données manquantes** : si son fichier n'est pas
  là, elle se désactive (log `WARN ... DESACTIVE`), `GET .../status` renvoie
  `ready:false`, et ses opérations renvoient **503** (`application/problem+json`).

### Démarrage ASYNCHRONE (important)
Le chargement lourd (téléchargement + construction graphe/index) **ne bloque pas**
le démarrage : Tomcat écoute immédiatement, puis `status.StartupOrchestrator`
(`@EventListener(ApplicationReadyEvent)`) lance un **thread de fond** qui fait, dans
l'ordre (RAM maîtrisée) : `ensureOsm → RoutingEngine.initialize → ensureBan →
AddressSearchService.initialize`. L'avancement est publié dans
`status.StatusRegistry` (états `WAITING/DOWNLOADING/INITIALIZING/READY/DISABLED/ERROR`
+ progression des téléchargements en octets). Le tableau de bord `/` et `/status`
lisent ce registre. **Ne pas remettre l'init lourde dans un `@PostConstruct`** : ça
re-bloquerait le démarrage et casserait l'affichage « en cours de chargement ».
`DataDownloadService` reporte la progression (octets téléchargés/total) au registre.

## 3. Stack & versions — et POURQUOI (pièges réels rencontrés)

| Lib | Version | Note critique |
|---|---|---|
| Java | **17** | Contrainte forte : voir piège #1 |
| Spring Boot | 3.4.1 | parent POM |
| GraphHopper | 11.0 (Java 17) | profil = encoded values + custom model, voir piège #2 |
| Timefold | 1.33.0 (Java 17) | starter Spring Boot, auto-config du `SolverManager` |
| Lucene | **9.12.3** (Java 11) | PAS 10.x : voir piège #1 |
| springdoc-openapi | **2.7.0** | PAS 2.8.x avec Spring Boot 3.4 : voir piège #3 |

### Piège #1 — Java 17 obligatoire (ne pas remonter Lucene en 10.x)
Lucene **10** est compilé pour **Java 21** (class file 65) ; GraphHopper 11 et
Timefold 1.33 sont en **Java 17** (61). La machine cible a un `JAVA_HOME` par
défaut sur **JDK 17**. Pour que `mvn spring-boot:run` fonctionne sans config, tout
le projet est calé sur **Java 17** + **Lucene 9.12.3**. **Ne pas** remonter Lucene
en 10.x sans passer toute la chaîne (et `JAVA_HOME`) en Java 21+.
> JDK installés sur la machine : `C:\Program Files\Java\jdk-17` (défaut) et `jdk-23`.

### Piège #2 — Profil GraphHopper 11
`new Profile("car")` **seul échoue** à l'exécution :
`Could not create weighting... At least one initial statement under 'speed' is required`.
En GH 11, un profil exige des *encoded values* + un *custom model*. Solution dans
`RoutingEngine.buildOrNull()` :
```java
gh.setEncodedValuesString("car_access, car_average_speed");
CustomModel cm = GHUtility.loadCustomModelFromJar("car.json"); // embarqué dans le jar GH
gh.setProfiles(new Profile("car").setCustomModel(cm));
```
Custom models dispo dans le jar : `com/graphhopper/custom_models/{car,bike,foot,truck,bus,...}.json`.

### Piège #3 — springdoc vs Spring Boot 3.4
springdoc **2.8.x** casse le démarrage avec Spring Boot 3.4 :
`No more pattern data allowed after {*...} or ** pattern element` (bean
`resourceHandlerMapping`). Utiliser **2.7.0** (aligné sur 3.4).

### Piège #4 — Swagger « Try it out » : NetworkError / CORS
**Ne PAS** figer `.servers("http://localhost:8080")` dans `OpenApiConfig` : si la
page Swagger est ouverte via une autre origine (`127.0.0.1`, nom de machine, IP
LAN), « Try it out » devient cross-origin → `NetworkError`. On laisse springdoc
générer une URL **relative** (basée sur la requête). En complément, `config.WebConfig`
active un **CORS** permissif (`/**`, toutes origines) pour les clients externes.
`localhost` ≠ `127.0.0.1` pour le navigateur (origines distinctes).

## 4. Données locales (`data/`)

| Fichier | Source | Taille | Cache dérivé |
|---|---|---|---|
| `france-latest.osm.pbf` | Geofabrik | ~5 Go | `data/graph-cache/` (GraphHopper) |
| `adresses-france.csv.gz` | BAN (data.gouv) | ~900 Mo | `data/address-index/` (Lucene) |

- **Téléchargement auto** au démarrage si manquant (`data.DataBootstrap`), seulement
  si ni le fichier source ni son cache dérivé n'existent.
- **Mise à jour auto** tous les **dimanches 8h Europe/Paris** (`data.DataUpdateScheduler`,
  cron `app.data.update-cron`) : re-téléchargement + reconstruction (graphe réimporté,
  index reconstruit) + bascule à chaud (`RoutingEngine.reload(true)`,
  `AddressSearchService.reload()`).
- Format BAN : CSV `;`-séparé, gzip. Colonnes utilisées : `numero, nom_voie,
  code_postal, nom_commune, lat, lon`.
- Mettre à jour les routes manuellement : remplacer le pbf, **supprimer
  `data/graph-cache/`**, relancer (sinon GraphHopper recharge l'ancien cache).

## 5. Endpoints (résumé — détail dans Swagger)

| Méthode | Chemin | Rôle |
|---|---|---|
| GET | `/` | **Tableau de bord HTML** (état en direct, auto-refresh 2 s) |
| GET | `/status` | état complet JSON (sous-systèmes, téléchargements, JVM, données) |
| GET | `/routing/status` | `{ready, profile}` |
| POST | `/routing/route` | itinéraire point à point (`from`/`to`) **ou multi-points ordonné** (`points[]`) → distance, durée, géométrie cumulées |
| POST | `/routing/matrix` | matrice N×N des temps (s) |
| POST | `/optimization/optimize` | tournée optimale (depot, vehicleCount, vehicleCapacity, visits[]) |
| GET | `/geocoding/status` | `{ready}` |
| GET | `/geocoding/search?q=&limit=` | autocomplétion d'adresse |

Score Timefold : `HardSoftLongScore` — dur = capacité dépassée ; souple = temps de
conduite total (minimisé). Geocoding : normalisation NFD (accents) + dernier mot en
préfixe.

**Géométrie — format au choix** (`routing.dto.GeometryFormat`) sur `/route` et
`/optimize` via le champ `geometryFormat` : `POINTS` (tableau `[lat,lon]`, défaut),
`POLYLINE` (chaîne encodée, algo Google/OSRM **précision 5**, ~1 m, décodable par
`@mapbox/polyline` ; ~8× plus compact, à privilégier pour les longs trajets) ou
`NONE`. Encodeur maison : `routing.GeometryEncoder.encodePolyline()` (deltas **lat,lon**,
facteur `1e5`). Sur `/route` la réponse expose `geometry` (rempli si POINTS) **et**
`geometryPolyline` — ce dernier est **toujours rempli sauf si NONE** (donc présent même en
POINTS : contrat attendu par les clients qui stockent une polyligne). Un champ `geometryFormat`
rappelle le format demandé. Enums tolérants à la casse (`accept-case-insensitive-enums`).
`includeGeometry` (déprécié) reste géré : true→POINTS, false→NONE.

**`/route` — mode multi-points ordonné** : en plus de `{from, to}` (point à point), le corps
accepte `points: [{lat,lon}, ...]` (≥ 2 éléments). L'itinéraire passe par **tous les points dans
l'ordre fourni** (aucune réoptimisation — pour réordonner, c'est `/optimize`). `distanceMeters`/
`durationSeconds` sont **cumulés**, et `geometry`/`geometryPolyline` forment une **trace continue**
(chaque paire consécutive est routée via `RoutingEngine.route()`, troncons concaténés en sautant le
point de jonction dupliqué). `points` prime sur `from`/`to`. Validation `@AssertTrue` dans
`RouteRequest` : il faut soit `from`+`to`, soit `points` (≥ 2), sinon **400**.

**Réponse `/optimize` enrichie** (pour affichage sur un site, l'API ne fait pas de carte) :
par tournée → `departureTime`, `returnTime`, `distanceMeters`, `drivingTimeSeconds`,
`serviceTimeSeconds`, **`geometry`/`geometryPolyline`** (trace COMPLÈTE de la tournée
depot→arrêts→depot, même règle de remplissage que `/route` : polyline sauf NONE — utile car les
polylignes par segment ne se concatènent pas) ; par arrêt → `legFromPrevious` {`distanceMeters`,
`durationSeconds`, `geometry` [lat,lon][]}, `cumulativeDistanceMeters`, `cumulativeDrivingSeconds`,
`arrivalTime`, `departureTime` ; + `returnLeg`. Entrée : `departureTime` (ISO, défaut now),
`serviceDurationSeconds` par visite (défaut 0), `vehicleCapacity` omis = illimité, `demand` défaut 0,
`includeGeometry` (défaut true ; mettre false pour alléger). La trace globale est accumulée à partir
de la géométrie brute de chaque segment, indépendamment du format demandé.

## 6. Build, run, test (Windows / PowerShell)

```powershell
mvn clean compile           # compile (JDK 17 par défaut suffit)
mvn spring-boot:run         # lance l'API (port 8080)
```
- 1er démarrage : télécharge ~6 Go puis construit graphe (plusieurs min, RAM ~) +
  index. Suivre les logs : `Telechargement ... Mo`, `GraphHopper pret`,
  `Index d'adresses pret (N documents)`.
- Démarrages suivants : rechargent les caches → rapides.
- Tester sans données / en isolation : `APP_AUTO_DOWNLOAD=false` (+ `SERVER_PORT=8081`,
  `APP_INDEX_DIR=...`, `APP_OSM_FILE=...` vers des chemins inexistants pour ne pas
  collisionner une instance déjà lancée — Lucene verrouille `address-index/write.lock`).

Config clé (`application.yml`, surchargée par variables d'env entre `${...}`) :
`app.data.auto-download`, `app.data.update-cron`, `app.routing.osm-file/osm-url/
graph-cache/profile/use-ch`, `app.geocoding.ban-file/ban-url/index-dir/
rebuild-on-start`, `timefold.solver.termination.spent-limit`, `server.port`.

## 7. Structure du code

```
bzh.stackbzh.org
├─ SpringOrgApplication        (main)
├─ config/   OpenApiConfig, SchedulingConfig(@EnableScheduling), StartupBanner
├─ common/   ApiExceptionHandler (503/400 → ProblemDetail)
├─ status/   StatusRegistry, ComponentState, StartupOrchestrator(async init),
│            StatusController(/status)
├─ data/     DataDownloadService, DataBootstrap(ensureOsm/ensureBan), DataUpdateScheduler(@Scheduled)
├─ routing/  RoutingEngine.initialize(), MatrixService, RoutingController, dto/
├─ optimization/ domain/(Location,Visit,Vehicle,VehicleRoutePlan), solver/(ConstraintProvider),
│               OptimizationService, OptimizationController, dto/
└─ geocoding/ AddressSearchService.initialize(), GeocodingController, dto/
resources/static/index.html   ← tableau de bord servi sur "/"
```
- Modèle Timefold : `Vehicle` (@PlanningEntity, `@PlanningListVariable visits`),
  `Visit` (fait, `@PlanningId`), `VehicleRoutePlan` (@PlanningSolution). La matrice
  est injectée dans chaque `Location.drivingTimeSeconds` avant résolution.
- L'init des moteurs n'est PAS dans `@PostConstruct` : elle est orchestrée en
  asynchrone (cf. § « Démarrage ASYNCHRONE »).

## 8. Conventions

- Réponses d'erreur : `ProblemDetail` (RFC 7807) via `common.ApiExceptionHandler`.
  Indisponibilité → **503** ; validation → **400**.
- Nouveau sous-système nécessitant des données : suivre le pattern « tolérant »
  (flag `isReady()`, log `WARN ... DESACTIVE`, exception dédiée mappée en 503,
  `reload()` pour la MAJ hebdo, `@DependsOn("dataBootstrap")`).
- **Et toujours : documenter en OpenAPI (cf. règle en tête de fichier).**

## 9. État connu / TODO possibles

- Routing GraphHopper & solveur Timefold validés à la **compilation** et au
  **démarrage du contexte** ; le build réel du graphe France (long) se fait au
  premier `spring-boot:run` avec données.
- Pistes : endpoint `POST /data/update` (déclencher la MAJ à la demande),
  fenêtres horaires (VRPTW), multi-dépôts, équilibrage des tournées, profil `bike`.
