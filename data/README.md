# Données locales

Place ici les deux fichiers de données (non versionnés, volumineux).
Tout est local : **aucune API externe n'est appelée à l'exécution.**

## 1. Réseau routier — `france-latest.osm.pbf`

Pour le routing et le calcul de matrices (GraphHopper).

- Télécharger : https://download.geofabrik.de/europe/france.html
- Fichier : `france-latest.osm.pbf` (~4 Go)
- Déposer ici : `data/france-latest.osm.pbf`

Au premier démarrage, GraphHopper construit un graphe (dossier `data/graph-cache/`).
C'est **long** (plusieurs minutes) et gourmand en RAM. Les démarrages suivants
rechargent ce cache et sont rapides.

> Pour mettre à jour les routes : remplace le `.osm.pbf`, supprime `data/graph-cache/`,
> et relance l'application.

## 2. Adresses — `adresses-france.csv.gz`

Pour la recherche d'adresse / autocomplétion (Base Adresse Nationale, Lucene).

- Télécharger : https://adresse.data.gouv.fr/data/ban/adresses/latest/csv
- Fichier : `adresses-france.csv.gz` (semicolon-separated, gzip, ~26 M adresses)
- Déposer ici : `data/adresses-france.csv.gz`

Au premier démarrage, l'application construit un index Lucene (`data/address-index/`).

> Pour mettre à jour les adresses : remplace le `.csv.gz` et relance avec
> `APP_INDEX_REBUILD=true` (ou supprime `data/address-index/`).
