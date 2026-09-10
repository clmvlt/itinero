package bzh.stackbzh.org.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI springOrgOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("spring-org API")
                        .version("0.1.0")
                        .description("""
                                API **100 % locale** (aucune dependance a une API externe a l'execution) reunissant trois briques :

                                1. **Routing** (`/routing`) — calcul d'itineraire et de matrices de temps via GraphHopper embarque, \
                                a partir d'un extrait OpenStreetMap de la France.
                                2. **Optimisation** (`/optimization`) — resolution de tournees (VRP/TSP) via Timefold : \
                                ordre de passage optimal de N points, sous contrainte de capacite, en minimisant le temps de conduite.
                                3. **Geocoding** (`/geocoding`) — recherche / autocompletion d'adresse via un index Lucene \
                                alimente par la Base Adresse Nationale (BAN).

                                ### Donnees
                                Les fichiers (OSM ~5 Go, BAN ~900 Mo) sont **telecharges automatiquement** au demarrage s'ils manquent. \
                                Ils sont ensuite **mis a jour automatiquement** par un pipeline commun : **(1) a chaque demarrage**, une fois \
                                les moteurs charges avec les donnees existantes (l'API reste servie pendant ce temps), si la source publie une \
                                version plus recente (requete HEAD, comparaison `Last-Modified` avec la version locale) ; **(2) tous les \
                                dimanches a 8h** (heure de Paris). Une mise a jour re-telecharge le fichier, reconstruit le graphe / l'index \
                                et bascule a chaud. Pendant la reconstruction du graphe routier, `/routing` et `/optimization` renvoient \
                                **503** ; le geocoding reste disponible pendant la reconstruction de son index. Chaque execution produit un \
                                **compte rendu** (declencheur, machine, versions publiees avant/apres, tailles, noeuds/aretes du graphe, \
                                adresses indexees, durees) publie sur le webhook Discord configure et resume dans `GET /status` \
                                (objet `dataUpdate`). Pendant qu'une brique n'a pas ses donnees, ses endpoints renvoient **503** \
                                (corps `application/problem+json`) ; consulter `GET /status` de chaque brique.

                                ### Codes de reponse transverses
                                - `200` succes
                                - `400` requete invalide (validation des champs)
                                - `503` sous-systeme indisponible (donnees absentes / en cours de (re)construction)
                                """)
                        .contact(new Contact().name("StackBZH"))
                        .license(new License().name("Proprietaire")));
    }
}
