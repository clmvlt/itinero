package bzh.stackbzh.org.geocoding;

import bzh.stackbzh.org.geocoding.dto.AddressResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/geocoding")
@Tag(name = "Geocoding", description = """
        Recherche / autocompletion d'adresse via un index Lucene alimente par la Base Adresse \
        Nationale (BAN). Tolerant aux accents (normalisation NFD) ; le dernier mot saisi est traite \
        en prefixe pour l'autocompletion type GPS. Necessite l'index charge (voir GET /geocoding/status). \
        Sinon : 503.""")
public class GeocodingController {

    private static final int MAX_LIMIT = 50;

    private final AddressSearchService service;

    public GeocodingController(AddressSearchService service) {
        this.service = service;
    }

    @GetMapping("/status")
    @Operation(summary = "Etat de l'index d'adresses",
            description = "Indique si l'index Lucene est charge (`ready=true`). Si `false`, `/search` renvoie 503.")
    @ApiResponse(responseCode = "200", description = "Etat retourne",
            content = @Content(schema = @Schema(example = "{\"ready\": true}")))
    public Map<String, Object> status() {
        return Map.of("ready", service.isReady());
    }

    @GetMapping("/search")
    @Operation(summary = "Rechercher une adresse",
            description = """
                    Recherche plein texte avec autocompletion. Renvoie les resultats les plus pertinents \
                    (tries par score decroissant), avec leurs coordonnees GPS.

                    **Niveau de resultat (voie vs adresse precise)** — automatique selon la requete :
                    - Si la requete ne contient **aucun numero de voie** (ex : `rue du bocage`), les resultats \
                    sont **agreges au niveau de la voie** : une seule entree par rue distincte (commune + code \
                    postal), `type=street`, `houseNumber` vide, libelle sans numero. On ne renvoie donc plus une \
                    longue liste de numeros d'une meme rue, mais la liste des rues qui existent.
                    - Si la requete contient un numero (ex : `12 rue du bocage`), les resultats sont des **adresses \
                    precises** (`type=housenumber`), comme auparavant. Un numero est un jeton de 1 a 4 chiffres \
                    (eventuellement suivi d'une lettre : `12b`) ; un code postal a 5 chiffres n'est pas considere \
                    comme un numero.

                    **Pertinence** — la voie dont le nom correspond exactement au texte saisi (numero ignore) \
                    est privilegiee, devant les correspondances partielles (ex : `rue du bocage` fait remonter \
                    les `Rue du Bocage` avant `Rue du Parc du Bocage`).

                    **Biais de proximite (optionnel)** — si `lat` et `lon` sont fournis, les resultats sont \
                    classes en favorisant les plus proches de cette position (distance reelle ponderant le score). \
                    Chaque resultat expose alors `distanceMeters`. La position **n'est pas obligatoire** ; sans \
                    elle, le classement reste purement textuel et `distanceMeters` vaut `null`. Fournir `lat` sans \
                    `lon` (ou l'inverse), ou des coordonnees hors bornes WGS84, renvoie **400**.

                    Exemples :
                    - `q=rue du bocage` -> liste des rues du Bocage (une par commune), sans numero
                    - `q=rue du bocage&lat=48.11&lon=-1.68` -> les rues du Bocage les plus proches d'abord
                    - `q=12 rue de la paix par` -> autocomplete adresse sur le dernier mot (`par`...)

                    Chaque mot complet doit matcher (AND) ; le dernier mot est traite en prefixe (pas de \
                    tolerance aux fautes de frappe : `bocaje` ne trouve pas `bocage`).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste de resultats (peut etre vide)"),
            @ApiResponse(responseCode = "400", description = "Parametres invalides (ex : lat sans lon, hors bornes)",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "Index d'adresses indisponible", content = @Content)
    })
    public List<AddressResult> search(
            @Parameter(description = "Texte recherche (adresse, rue, ville...). L'autocompletion s'applique au dernier mot.",
                    example = "rue du bocage")
            @RequestParam("q") String query,
            @Parameter(description = "Nombre maximum de resultats (1 a " + MAX_LIMIT + ").", example = "10")
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @Parameter(description = "Latitude WGS84 de la position de reference (optionnel). Si fournie, `lon` doit l'etre aussi.",
                    example = "48.1147")
            @RequestParam(value = "lat", required = false) Double lat,
            @Parameter(description = "Longitude WGS84 de la position de reference (optionnel). Si fournie, `lat` doit l'etre aussi.",
                    example = "-1.6794")
            @RequestParam(value = "lon", required = false) Double lon) {
        if ((lat == null) != (lon == null)) {
            throw new IllegalArgumentException("Pour un biais de proximite, fournir lat ET lon (ou aucun des deux).");
        }
        if (lat != null && (lat < -90 || lat > 90 || lon < -180 || lon > 180)) {
            throw new IllegalArgumentException("Position invalide : lat doit etre dans [-90,90] et lon dans [-180,180].");
        }
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return service.search(query, safeLimit, lat, lon);
    }
}
