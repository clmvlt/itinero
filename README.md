# spring-org

API Spring Boot **100 % locale** : une fois les données téléchargées, elle ne dépend
d'**aucune API externe** à l'exécution. Elle réunit trois briques, en remplacement de
l'outil VROOM en ligne de commande.

| Brique | Techno | Rôle |
|---|---|---|
| **Routing** | GraphHopper (embarqué) | itinéraires routiers réels + matrices de temps |
| **Optimisation (VRP/TSP)** | Timefold | ordre de passage optimal de N points |
| **Géocodage** | Lucene + BAN | recherche / autocomplétion d'adresse |

Tout fonctionne à partir de fichiers locaux : un extrait OpenStreetMap de la France
(routing) et la Base Adresse Nationale (géocodage).

---

## Prérequis

- **Java 17+** (testé sur JDK 17 et 23) — tourne avec le JDK par défaut, pas de `JAVA_HOME` à régler.
- **Maven 3.8+**
- **~16 Go de RAM** (graphe routier France entière) et **~15 Go de disque** (données + caches).

> La stack est volontairement calée sur Java 17 (Lucene 9.x). Ne pas monter Lucene en
> 10.x sans passer toute la chaîne en Java 21+ (voir `CLAUDE.md`).

---

## Lancer l'API

```bash
mvn spring-boot:run
```

L'API écoute sur **http://localhost:8080** et répond immédiatement.

Au **premier démarrage**, elle télécharge automatiquement les fichiers manquants puis
construit ses caches (graphe routier + index d'adresses) — cela prend plusieurs minutes :

- `data/france-latest.osm.pbf` (~5 Go, Geofabrik)
- `data/adresses-france.csv.gz` (~900 Mo, Base Adresse Nationale)

Le chargement se fait **en arrière-plan**. Suivre l'avancement sur le tableau de bord
(`/`) ou via `GET /status`. Les démarrages suivants rechargent les caches et sont rapides.

> L'API démarre **même si une donnée manque** : la brique concernée se désactive,
> son `/status` renvoie `ready:false` et ses appels renvoient **503**.

Les données sont **mises à jour automatiquement** chaque dimanche à 8h (heure de Paris).

---

## Explorer l'API

- **Tableau de bord (état en direct)** : http://localhost:8080/
- **Swagger UI** : http://localhost:8080/swagger-ui.html
- **Spec OpenAPI (JSON)** : http://localhost:8080/v3/api-docs
- **Statut JSON** : http://localhost:8080/status

La documentation OpenAPI est maintenue exhaustive : chaque endpoint et chaque champ y
est décrit avec des exemples. C'est la référence à jour de l'API.

---

## Endpoints

### Routing
- `GET /routing/status`
- `POST /routing/route` — itinéraire **point à point** (`from`/`to`) **ou multi-points
  ordonné** (`points[]`, ≥ 2). Renvoie distance, durée et géométrie cumulées.
  ```json
  { "points": [
      {"lat": 48.1173, "lon": -1.6778},
      {"lat": 47.2184, "lon": -1.5536}
    ],
    "geometryFormat": "POINTS" }
  ```
  `geometryFormat` : `POINTS` (tableau `[lat,lon]`, défaut), `POLYLINE` (chaîne encodée
  compacte) ou `NONE`. `geometryPolyline` est toujours renseigné sauf en `NONE`.
- `POST /routing/matrix` — matrice N×N des temps de trajet.

### Optimisation de tournée
- `POST /optimization/optimize` — ordre de passage optimal depuis/vers un dépôt commun.
  ```json
  {
    "depot": {"lat": 48.1173, "lon": -1.6778},
    "vehicleCount": 1,
    "visits": [
      {"id": "A", "name": "Client A", "lat": 47.2184, "lon": -1.5536},
      {"id": "B", "name": "Client B", "lat": 48.5734, "lon": 7.7521}
    ]
  }
  ```
  Réponse enrichie pour l'affichage : tournées ordonnées par véhicule, avec par segment
  distance/durée/géométrie, par arrêt les cumuls et heures d'arrivée/départ, et la
  **géométrie complète de la tournée** (`routes[].geometry` / `geometryPolyline`).
  `vehicleCapacity` omis = illimité ; `demand`/`serviceDurationSeconds` omis = 0.

### Géocodage
- `GET /geocoding/status`
- `GET /geocoding/search?q=rue du bocage&limit=10`

---

## Configuration

Tout se règle dans `src/main/resources/application.yml`, surchargé par variables
d'environnement :

| Variable | Défaut | Rôle |
|---|---|---|
| `SERVER_PORT` | `8080` | port HTTP |
| `APP_AUTO_DOWNLOAD` | `true` | télécharger / mettre à jour les données automatiquement |
| `APP_UPDATE_CRON` | `0 0 8 * * SUN` | planning de mise à jour |
| `APP_OSM_URL` | Geofabrik France | source du réseau routier |
| `APP_BAN_URL` | BAN France | source des adresses |
| `timefold.solver.termination.spent-limit` | `5s` | temps alloué au solveur |

Lancer sans données (tests / CI) : `APP_AUTO_DOWNLOAD=false`.

---

## Données

Voir [`data/README.md`](data/README.md). Les fichiers volumineux (`.pbf`, CSV, caches)
ne sont **pas versionnés** (cf. `.gitignore`). Mise à jour manuelle des routes :
remplacer le `.pbf`, supprimer `data/graph-cache/`, relancer.

---

## Déploiement

Un script `deploy/deploy.py` build le jar, l'envoie par SFTP sur le serveur et redémarre
le service systemd.

```bash
pip install -r deploy/requirements.txt
cp deploy/.env.example deploy/.env   # puis renseigner DEPLOY_PASSWORD
python deploy/deploy.py
```

La configuration (hôte, identifiants, chemin distant, service) se met dans `deploy/.env`,
qui n'est **pas versionné**. Options : `--no-build`, `--with-tests`, `--no-restart`.

---

## Architecture en bref

- L'**optimisation s'appuie sur le routing** (matrice de temps réels). Si le routing est
  indisponible, `/optimize` renvoie 503.
- GraphHopper Core n'a pas d'API matrice : elle est calculée par routage pair-à-pair,
  rendu rapide par les *Contraction Hierarchies*.
- Le chargement lourd (graphe, index) est **asynchrone** : le serveur répond tout de suite
  et publie son avancement dans `/status`.
- Erreurs au format RFC 7807 (`application/problem+json`) : indisponibilité → 503,
  validation → 400.

Détails complets d'architecture et pièges connus : voir [`CLAUDE.md`](CLAUDE.md).
