# Prompt frontend — tournées avec chargement et enlèvement

> À copier-coller dans l'IA (ou à donner au développeur) qui travaille sur le **frontend** appelant
> l'API spring-org. Décrit **ce qui change côté client** et ce qu'il faut en faire à l'écran.
> Référence de contrat détaillée : `docs/prompt-shipments.md` et la spec OpenAPI `GET /v3/api-docs`.

---

## 1. En une phrase

Les deux routes d'optimisation acceptent désormais des **missions appairées** : une marchandise
**chargée à un endroit** puis **déposée à un autre**, avec la garantie que les deux arrêts sont faits par
le **même véhicule** et que le **chargement passe avant l'enlèvement**.

## 2. Compatibilité : rien ne casse

**Tout le code existant continue de fonctionner à l'identique.** Les changements sont purement additifs :

- `visits[]` fonctionne exactement comme avant ;
- les réponses gagnent des champs, aucun n'est supprimé ni renommé ;
- les nouveaux champs d'arrêt (`shipmentId`, `stopType`) valent `null` pour les points ordinaires ;
- une requête sans missions produit les mêmes tournées, dans le même temps qu'avant.

Tu peux donc livrer la nouvelle fonctionnalité sans toucher aux écrans existants.

## 3. Le concept à faire passer dans l'UI

Aujourd'hui, l'utilisateur saisit une liste de **points à visiter** indépendants : le solveur les ordonne
comme il veut.

Une **mission** est différente : c'est **un objet métier unique avec deux arrêts liés**. Exemples :
transporter une palette d'un fournisseur vers un client, un déménagement, une navette inter-sites, une
course A → B.

> ⚠️ **Le piège à éviter absolument** : ne modélise pas une mission comme deux entrées de `visits[]`.
> L'API n'aurait alors aucun moyen de savoir qu'elles sont liées, et pourrait très bien livrer avant
> d'avoir chargé, ou répartir les deux arrêts sur deux camions différents. C'est précisément le problème
> que `shipments[]` résout.

Dans l'interface, une mission devrait donc se saisir et s'afficher **comme une seule ligne** (« Palette
Dupont : de X à Y »), même si elle produit deux arrêts dans la tournée.

## 4. Ce qui change dans la REQUÊTE

Routes concernées : `POST /optimization/optimize` et `POST /optimization/dispatch`.

### Nouveau champ `shipments[]` (facultatif)

```jsonc
{
  "depot": {"lat": 48.1173, "lon": -1.6778},
  "departureTime": "2026-06-15T08:00:00",
  "geometryFormat": "POLYLINE",
  "visits": [                          // arrêts indépendants — inchangé
    {"id": "A", "name": "Passage courrier", "lat": 48.11, "lon": -1.68}
  ],
  "shipments": [                       // NOUVEAU — mélangeable librement avec visits
    {
      "id": "M1",                      // facultatif (auto "s<index>") — sert à recoller les 2 arrêts
      "name": "Palette Dupont",        // facultatif, libellé d'affichage
      "pickup":   {"lat": 48.12, "lon": -1.70, "demand": 2, "serviceDurationSeconds": 600},
      "delivery": {"lat": 47.22, "lon": -1.55, "timeWindowEnd": "2026-06-15T12:00:00"}
    }
  ]
}
```

`pickup` et `delivery` sont des **points complets**, avec la même structure qu'une entrée de `visits[]` :
`lat`, `lon` (requis), `id`, `name`, `demand`, `serviceDurationSeconds`, `timeWindowStart`,
`timeWindowEnd`. Chaque extrémité a donc **sa propre fenêtre horaire** — on peut exiger un chargement le
matin et une dépose avant midi. Prévois-le dans le formulaire : deux blocs horaires, pas un seul.

### Une mission peut n'avoir qu'une extrémité

- `pickup` absent → la marchandise est **chargée au dépôt** au départ (livraison classique).
- `delivery` absent → elle est **ramenée au dépôt** en fin de tournée (collecte).

Aucun ordre n'est alors imposé (le dépôt encadre déjà la tournée). C'est utile si ton modèle de données
traite toutes les courses comme des missions : tu peux tout envoyer en `shipments[]` sans cas particulier.

### Autres changements de la requête

| Champ | Changement |
|---|---|
| `visits` | **N'est plus obligatoire.** Il faut au moins un élément **au total** entre `visits` et `shipments`. |
| `maxSolvingSeconds` | **Nouveau sur `/optimize`** (existait déjà sur `/dispatch`). Entier ≥ 1, plafonné à 60 s. |

## 5. Ce qui change dans la RÉPONSE

| Champ | Où | À quoi ça sert |
|---|---|---|
| `shipmentId` | chaque `stops[]` | Relie les deux arrêts d'une même mission. `null` = arrêt ordinaire. |
| `stopType` | chaque `stops[]` | `"PICKUP"` \| `"DELIVERY"` \| `null`. Pour l'icône / la couleur. |
| `pairingViolations` | racine | Missions dont la règle n'est pas tenue. **Doit valoir 0.** |
| `solvingTimeSeconds` | racine | Budget réellement utilisé. `null` = mode rapide historique. |
| `shipmentId`, `stopType` | chaque `skippedVisits[]` | Identifie la mission écartée. |
| `reason: "PAIRED_POINT_SKIPPED"` | `skippedVisits[]` | Nouvelle valeur possible (cf. §6). |

Le reste de la réponse est inchangé : `routes[].stops[]` reste **l'ordre de passage**, avec les heures
(`arrivalTime`, `serviceStartTime`, `departureTime`), les cumuls, les géométries, `feasible`,
`timeWindowViolations`, `balance` sur `/dispatch`, etc.

**Garantie d'affichage** : dans une tournée, le `stops[]` d'un `shipmentId` donné contient toujours le
`PICKUP` **avant** le `DELIVERY`, dans la **même** `routes[]`. D'autres arrêts peuvent s'intercaler entre
les deux — c'est normal et souhaitable, le camion transporte plusieurs marchandises à la fois.

## 6. Cas limites à gérer

**Une mission est écartée en entier, jamais à moitié.** Si une seule des deux extrémités n'est pas
rattachable au réseau routier (coordonnées en mer, hors zone, trop loin d'une route), **les deux** arrêts
sont écartés et listés dans `skippedVisits[]`. Celui qui était valide porte
`reason: "PAIRED_POINT_SKIPPED"`. Message utilisateur suggéré :

> « Mission *Palette Dupont* ignorée : l'adresse de chargement n'a pas pu être localisée sur une route. »

**Une fenêtre horaire intenable n'est pas une erreur.** La tournée est quand même renvoyée, avec
`feasible: false` et le détail dans `stops[].timeWindowStatus` (`LATE` / `WAITING_TOO_LONG`). À afficher
comme un avertissement, pas comme un échec.

**Arbitrage assumé par l'API** : face au choix entre livrer en retard et casser une mission, l'API
**garde toujours la mission** et accepte le retard. Si ton utilisateur voit un retard alors qu'un autre
ordre « aurait marché », c'est en général ça — et c'est voulu.

**Nouveaux cas de 400** (en plus des existants) :

- `visits` **et** `shipments` tous les deux vides ;
- une mission sans `pickup` ni `delivery` ;
- des identifiants dupliqués, dès qu'il y a des missions (l'appairage se fait par identifiant) — veille
  donc à générer des `id` uniques si tu en fournis ;
- `maxSolvingSeconds` < 1.

Les erreurs sont en `application/problem+json` : lis `title`, `detail`, `status`.

## 7. Impact sur les temps de réponse (important pour l'UX)

Une mission rend le calcul nettement plus difficile pour le solveur. Conséquence :

| Requête | Temps de résolution |
|---|---|
| `/optimize` **sans** missions | ~1 s (inchangé) |
| `/optimize` **avec** missions | 5 s par défaut, jusqu'à 60 s via `maxSolvingSeconds` |
| `/dispatch` | 10 s par défaut, jusqu'à 60 s |

La requête HTTP dure **au moins** ce temps, auquel s'ajoute le calcul de la matrice des distances. Donc :

- **timeout client ≥ 90 s** dès qu'il y a des missions ou un `/dispatch` ;
- affiche un état « calcul en cours » explicite (pas un spinner muet de 30 s) ;
- si tu exposes un réglage « qualité du calcul », mappe-le sur `maxSolvingSeconds` (5 s = rapide,
  20-30 s = soigné) et affiche `solvingTimeSeconds` renvoyé comme confirmation.

## 8. Limite à connaître : la capacité

`vehicleCapacity` compare une **somme** de `demand` sur toute la tournée — ce n'est **pas** un suivi de la
charge à bord. Un camion de 10 palettes ne peut donc pas enchaîner plus de 10 palettes sur la journée,
même s'il décharge entre-temps. Deux conséquences pratiques :

- mets `demand` sur le `pickup` et laisse `delivery.demand` à `0`, sinon la charge compte double ;
- ne promets pas dans l'UI une gestion fine du remplissage du camion : ce n'est pas ce que fait l'API.

## 9. Checklist d'intégration

- [ ] Saisie d'une mission = **une** ligne avec deux adresses et deux créneaux horaires optionnels.
- [ ] Envoi en `shipments[]`, jamais en deux `visits[]`.
- [ ] `id` de mission unique et stable, pour recoller les arrêts au retour.
- [ ] À l'affichage, grouper/relier les arrêts par `shipmentId` ; icône selon `stopType`.
- [ ] Vérifier **`feasible`**, **`pairingViolations === 0`** et **`skippedVisits`** avant d'afficher.
- [ ] Timeout ≥ 90 s + état « calcul en cours ».
- [ ] `departureTime` renseigné dès qu'une extrémité a une fenêtre horaire.
- [ ] Ne supposer aucune symétrie des temps de trajet (sens uniques : A→B ≠ B→A).

## 10. Exemple complet

Requête `/optimization/optimize` — deux missions croisées :

```json
{
  "depot": {"lat": 48.1173, "lon": -1.6778},
  "departureTime": "2026-06-15T08:00:00",
  "geometryFormat": "POLYLINE",
  "shipments": [
    {"id": "M1", "name": "Rennes vers Nantes",
     "pickup":   {"id": "M1-P", "lat": 48.1200, "lon": -1.7000},
     "delivery": {"id": "M1-D", "lat": 47.2184, "lon": -1.5536}},
    {"id": "M2", "name": "Nantes vers Rennes",
     "pickup":   {"id": "M2-P", "lat": 47.2220, "lon": -1.5600},
     "delivery": {"id": "M2-D", "lat": 48.1100, "lon": -1.6600}}
  ]
}
```

Réponse (abrégée, valeurs réelles mesurées) :

```json
{
  "feasible": true,
  "pairingViolations": 0,
  "solvingTimeSeconds": 5,
  "routes": [
    {
      "vehicleId": "vehicle-0",
      "durationSeconds": 9874,
      "distanceMeters": 221000,
      "stops": [
        {"visitId": "M1-P", "shipmentId": "M1", "stopType": "PICKUP",   "arrivalTime": "2026-06-15T08:04:52"},
        {"visitId": "M2-P", "shipmentId": "M2", "stopType": "PICKUP",   "arrivalTime": "2026-06-15T09:21:01"},
        {"visitId": "M1-D", "shipmentId": "M1", "stopType": "DELIVERY", "arrivalTime": "2026-06-15T09:23:23"},
        {"visitId": "M2-D", "shipmentId": "M2", "stopType": "DELIVERY", "arrivalTime": "2026-06-15T10:38:17"}
      ]
    }
  ],
  "skippedVisits": []
}
```

Lecture : le camion charge à Rennes, descend à Nantes où il charge la 2ᵉ mission et livre la 1ʳᵉ, puis
remonte livrer à Rennes. Chaque `PICKUP` précède bien son `DELIVERY`.

Pour comparaison, les **mêmes 4 points envoyés en `visits[]`** donnent l'ordre
`M2-D, M2-P, M1-D, M1-P` — les deux livraisons avant leurs chargements, soit 157 s de moins mais une
tournée **inexécutable**. C'est toute la différence que fait `shipments[]`.
