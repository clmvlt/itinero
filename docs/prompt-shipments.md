# Prompt — missions appairées (chargement → enlèvement)

> À copier-coller dans une IA (ou à donner à un développeur) qui doit intégrer les **tournées avec
> chargement et enlèvement** de l'API spring-org. Ce document ne couvre que cette fonctionnalité ;
> pour le reste de l'API, voir `/prompt.html` et la spec OpenAPI.

L'API est 100 % locale (routing GraphHopper + solveur Timefold, aucune dépendance externe à l'exécution).
Base de production : `https://ors.stack.bzh` (en local : `http://localhost:8080`).
**La spec OpenAPI (`GET /v3/api-docs`, Swagger UI sur `/swagger-ui.html`) est la source de vérité du
contrat** ; ce document explique le *pourquoi* et les cas limites.

---

## 1. À quoi ça sert

Une **mission** (`shipment`) représente une marchandise **chargée à un endroit** puis **déposée à un
autre**. Deux règles doivent être garanties, qu'une simple liste de points ne permet pas d'exprimer :

1. les deux arrêts sont servis par le **même véhicule** — on ne décharge pas ce qu'un autre camion
   transporte ;
2. le **chargement passe avant l'enlèvement** dans l'ordre de passage.

Cas d'usage typiques : transport de palettes d'un fournisseur vers un client, déménagement, navette
inter-sites, enlèvement de déchets vers un point de traitement, course A → B.

C'est une **option** : le champ `shipments[]` s'ajoute à `visits[]` sur les deux routes d'optimisation
existantes. **Une requête sans `shipments` se comporte exactement comme avant.**

## 2. Pourquoi une option, et pas une route dédiée

L'objectif d'optimisation ne change pas — c'est toujours « minimiser le temps » (`/optimize`) ou
« équilibrer les tournées » (`/dispatch`). Seules **deux contraintes dures** s'ajoutent. Une route
séparée aurait dupliqué le choix « ordonner » vs « répartir » sans rien apporter :

| Besoin | Route |
|---|---|
| Une tournée (ou minimiser le temps total) avec des missions | `POST /optimization/optimize` |
| Répartir des missions entre K chauffeurs, tournées équilibrées | `POST /optimization/dispatch` |

## 3. Fonctionnement interne, étape par étape

1. **Validation du corps** (Bean Validation). `depot` requis ; au moins un élément entre `visits` et
   `shipments` ; chaque mission a au moins un `pickup` ou un `delivery` ; `timeWindowStart` ≤
   `timeWindowEnd`. Sinon **400** (`application/problem+json`).
2. **Aplatissement** : chaque mission devient un ou deux points. Les identifiants manquants sont générés
   (`s<index>-pickup`, `s<index>-delivery`). Dès qu'une mission est présente, **les identifiants doivent
   être uniques** dans toute la requête (l'appairage se fait par identifiant, et la matrice des temps est
   indexée dessus) → doublon = **400**.
3. **Rattachement au réseau routier** : le dépôt est testé (non rattachable → **400**) ; chaque point
   aussi. Un point non rattachable est écarté et listé dans `skippedVisits[]`.
4. **Écart atomique des missions** : si **une** extrémité est écartée, **l'autre l'est aussi**, avec
   `reason = PAIRED_POINT_SKIPPED`. Garder une extrémité orpheline rendrait la contrainte dure
   insatisfiable et toute la tournée `feasible=false` sans explication utile.
5. **Matrice des temps réels** : (N+1)×(N+1) routages GraphHopper. Elle peut être **asymétrique** (sens
   uniques) — ne supposez aucune symétrie côté client.
6. **Résolution Timefold** avec deux contraintes dures supplémentaires (cf. §4).
7. **Réponse** : tournées ordonnées, heures recalculées à partir des durées de routage réelles, chaque
   arrêt portant `shipmentId` et `stopType`.

## 4. Comment la règle est garantie (et pourquoi elle n'est jamais bradée)

Deux contraintes **dures** :

| Contrainte | Déclenchement |
|---|---|
| `Chargement et enlevement sur des vehicules differents` | les deux extrémités ne sont pas sur le même véhicule |
| `Enlevement avant le chargement` | même véhicule, mais l'enlèvement est positionné avant le chargement |

**Point important pour interpréter le score.** Le score Timefold n'a qu'**un seul niveau dur**, partagé
avec le retard sur fenêtre horaire — qui se compte **en secondes** (couramment des milliers). Si les
pénalités d'appairage valaient « 1 », le solveur casserait rationnellement une mission pour éviter dix
minutes de retard : score correct, résultat métier faux. Elles sont donc exprimées **dans la même unité** :
casser une mission coûte l'équivalent de 2 jours de retard, une inversion d'ordre au moins 1 journée
(+ 1 min par position d'écart, ce qui donne au solveur un gradient pour réparer). Conséquence pratique :
**face à un arbitrage, l'API préfère toujours livrer en retard plutôt que casser une mission.**

## 5. Contrat de requête

Champs ajoutés à `OptimizeRequest` et `DispatchRequest` :

| Champ | Type | Requis | Défaut | Rôle |
|---|---|---|---|---|
| `shipments` | `ShipmentDto[]` | non | `[]` | Missions appairées. Mélangeable avec `visits`. |
| `visits` | `VisitDto[]` | non\* | `[]` | Arrêts indépendants (comportement historique). |
| `maxSolvingSeconds` | integer ≥ 1 \| null | non | 5 (`/optimize` avec missions), 10 (`/dispatch`) | Budget de résolution. Plafonné silencieusement à 60 s. |

\* Au moins un élément **au total** entre `visits` et `shipments`, sinon **400**.

`ShipmentDto` :

| Champ | Type | Requis | Rôle |
|---|---|---|---|
| `id` | string \| null | non | Identifiant de la mission, repris dans `stops[].shipmentId`. Auto-généré `s<index>`. |
| `name` | string \| null | non | Libellé d'affichage (ex. « Palette Dupont »). Sans effet sur l'optimisation. |
| `pickup` | `VisitDto` \| null | non\*\* | Arrêt de **chargement**. Absent = chargé au dépôt au départ. |
| `delivery` | `VisitDto` \| null | non\*\* | Arrêt d'**enlèvement**. Absent = ramené au dépôt en fin de tournée. |

\*\* Au moins une des deux extrémités, sinon **400**.

Chaque extrémité est un `VisitDto` **complet** : `lat`/`lon` (requis), `id`, `name`, `demand`,
`serviceDurationSeconds`, `timeWindowStart`, `timeWindowEnd`. Les deux extrémités ont donc **leurs propres
fenêtres horaires** — on peut exiger un chargement le matin et une dépose l'après-midi.

### Deux pièges à connaître

- **`demand` n'est pas un suivi de charge à bord.** Il garde sa sémantique historique : une **somme** sur
  toute la tournée, comparée à `vehicleCapacity`. Le porter sur le `pickup` et laisser le `delivery` à `0`,
  sinon la charge est comptée deux fois. Conséquence : un camion de 10 palettes ne peut pas enchaîner plus
  de 10 palettes sur la journée, même s'il décharge entre-temps (cf. §8).
- **Mission à une seule extrémité = arrêt simple.** Aucune contrainte d'ordre n'est ajoutée, puisque le
  dépôt encadre déjà la tournée. C'est la façon d'exprimer une livraison classique (chargée au dépôt) ou
  une collecte (vidée au dépôt) sans changer de format de données côté client.

## 6. Contrat de réponse

Champs ajoutés :

| Champ | Où | Rôle |
|---|---|---|
| `pairingViolations` | racine | Missions dont la règle n'est pas tenue. **0 attendu.** Recompté sur la réponse, indépendamment du score. |
| `solvingTimeSeconds` | racine (`/optimize`) | Budget réellement alloué. `null` = terminaison globale (1 s), cas d'une requête sans mission. |
| `shipmentId` | `stops[]`, `skippedVisits[]` | Mission dont l'arrêt est une extrémité. `null` = arrêt simple. |
| `stopType` | `stops[]`, `skippedVisits[]` | `PICKUP` \| `DELIVERY` \| `null`. |
| `reason = PAIRED_POINT_SKIPPED` | `skippedVisits[]` | Ce point était bon, mais l'autre extrémité de sa mission ne l'était pas. |

`feasible = false` si une contrainte dure est violée, **y compris** une mission cassée. Vérifier
`feasible`, `pairingViolations`, `timeWindowViolations` **et** `skippedVisits` avant d'afficher.

## 7. Codes de réponse

| Code | Quand | Corps |
|---|---|---|
| 200 | Tournées calculées (même partiellement infaisables) | `OptimizeResponse` / `DispatchResponse` |
| 400 | Dépôt manquant ou non rattachable ; `visits` **et** `shipments` vides ; mission sans `pickup` ni `delivery` ; identifiant dupliqué (requête avec missions) ; `timeWindowStart` > `timeWindowEnd` ; `maxWaitingSeconds` < 0 ; `maxSolvingSeconds` < 1 ; (`/dispatch`) `vehicleCount` absent ou < 1 | `ProblemDetail` |
| 503 | Routing indisponible (données absentes ou graphe en (re)construction) | `ProblemDetail` |

Une fenêtre horaire intenable ou une mission écartée ne sont **pas** des erreurs : 200 + `feasible=false`
ou + `skippedVisits`.

## 8. Performance et réglages serveur

Les missions rendent le problème nettement plus difficile : l'heuristique de construction ignore la
précédence, et **tout mouvement inter-véhicules casse une paire** — la quasi-totalité de ces mouvements est
donc rejetée par le solveur. D'où un budget de résolution plus généreux dès qu'il y a des missions.

| Clé `application.yml` | Variable d'env | Défaut | Rôle |
|---|---|---|---|
| `app.optimization.shipments.default-solving-seconds` | `APP_SHIPMENTS_SOLVING_SECONDS` | 5 | Budget de `/optimize` **quand la requête contient des missions** |
| `app.optimization.shipments.max-solving-seconds` | `APP_SHIPMENTS_MAX_SOLVING_SECONDS` | 60 | Plafond |
| `app.optimization.dispatch.default-solving-seconds` | `APP_DISPATCH_SOLVING_SECONDS` | 10 | Budget de `/dispatch` |
| `app.optimization.dispatch.max-solving-seconds` | `APP_DISPATCH_MAX_SOLVING_SECONDS` | 60 | Plafond |

Ordres de grandeur : ~20 missions (40 arrêts) sont confortables avec 5 s ; au-delà de ~100 missions
(200 arrêts), prévoir 30-60 s et un **timeout client ≥ 90 s** (le temps de la matrice s'ajoute au budget).
La requête HTTP dure au moins `solvingTimeSeconds`.

## 9. Exemple

Requête — deux missions et un arrêt simple, une dépose avant midi :

```json
{
  "depot": {"lat": 48.1173, "lon": -1.6778},
  "departureTime": "2026-06-15T08:00:00",
  "geometryFormat": "POLYLINE",
  "maxSolvingSeconds": 10,
  "shipments": [
    {
      "id": "M1", "name": "Palette Dupont",
      "pickup":   {"lat": 48.12, "lon": -1.70, "demand": 2, "serviceDurationSeconds": 600},
      "delivery": {"lat": 47.22, "lon": -1.55, "timeWindowEnd": "2026-06-15T12:00:00"}
    },
    {
      "id": "M2",
      "pickup":   {"lat": 48.09, "lon": -1.65},
      "delivery": {"lat": 48.57, "lon": 7.75}
    }
  ],
  "visits": [ {"id": "A", "name": "Passage courrier", "lat": 48.11, "lon": -1.68} ]
}
```

Réponse (abrégée) :

```json
{
  "feasible": true,
  "pairingViolations": 0,
  "timeWindowViolations": 0,
  "solvingTimeSeconds": 10,
  "routes": [
    {
      "vehicleId": "vehicle-0",
      "stops": [
        {"visitId": "M1-pickup",   "shipmentId": "M1", "stopType": "PICKUP",   "arrivalTime": "2026-06-15T08:21:00"},
        {"visitId": "A",           "shipmentId": null, "stopType": null,       "arrivalTime": "2026-06-15T08:40:00"},
        {"visitId": "M1-delivery", "shipmentId": "M1", "stopType": "DELIVERY", "arrivalTime": "2026-06-15T09:55:00"},
        {"visitId": "M2-pickup",   "shipmentId": "M2", "stopType": "PICKUP",   "arrivalTime": "2026-06-15T10:30:00"}
      ]
    }
  ],
  "skippedVisits": []
}
```

**Lecture côté client** : pour chaque `shipmentId`, l'arrêt `PICKUP` apparaît toujours avant le `DELIVERY`,
dans la **même** `routes[]`. Les arrêts d'autres missions peuvent s'intercaler entre les deux — c'est
normal et souhaitable (le camion transporte plusieurs marchandises à la fois).

## 10. Consignes d'intégration

- Dès qu'une marchandise doit être prise quelque part puis déposée ailleurs, utiliser `shipments` — **et
  non deux entrées de `visits`**, qui n'apporteraient aucune garantie d'ordre ni de véhicule.
- Toujours vérifier `pairingViolations == 0`, `feasible` et `skippedVisits` avant d'afficher.
- Relier les deux arrêts via `stops[].shipmentId` ; l'icône / la couleur peut suivre `stopType`.
- Renseigner `departureTime` dès qu'une extrémité a une fenêtre horaire.
- Prévoir un timeout HTTP large et un état « calcul en cours » pendant `solvingTimeSeconds`.
- Ne supposer aucune symétrie de la matrice des temps.

## 11. Limites connues

- **Pas de suivi de la charge à bord.** `vehicleCapacity` compare une **somme** sur la tournée, pas le pic
  de charge : un camion ne peut donc pas « recharger » après avoir déchargé. Si ce besoin apparaît, il
  faudra une contrainte de charge cumulée (demandes signées +q / −q et pénalisation du pic).
- **Pas de mouvement de solveur spécialisé** pour les paires : au-delà de ~100 missions la qualité se
  dégrade à budget constant. Pistes : `SubListChangeMove` (déplace un bloc contigu sans le casser),
  *nearby selection*, ruin & recreate, ou un mouvement custom déplaçant une mission entière.
- **Pas de contrainte de précédence entre missions distinctes** (ex. « M2 après M1 ») ni de précédence
  immédiate (le chargement et l'enlèvement d'une même mission peuvent être séparés par d'autres arrêts).
- **Un seul dépôt**, commun à tous les véhicules.
