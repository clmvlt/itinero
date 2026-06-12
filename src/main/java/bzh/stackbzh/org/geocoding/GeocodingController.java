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
                    Recherche plein texte avec autocompletion. Renvoie les adresses les plus pertinentes \
                    (triees par score decroissant), avec leurs coordonnees GPS.

                    Exemples :
                    - `q=rue du bocage` -> rues correspondantes dans toutes les communes
                    - `q=12 rue de la paix par` -> autocomplete sur le dernier mot (`par`...)

                    Chaque mot complet doit matcher (AND) ; le dernier mot est un prefixe.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste d'adresses (peut etre vide)"),
            @ApiResponse(responseCode = "503", description = "Index d'adresses indisponible", content = @Content)
    })
    public List<AddressResult> search(
            @Parameter(description = "Texte recherche (adresse, rue, ville...). L'autocompletion s'applique au dernier mot.",
                    example = "rue du bocage")
            @RequestParam("q") String query,
            @Parameter(description = "Nombre maximum de resultats (1 a " + MAX_LIMIT + ").", example = "10")
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return service.search(query, safeLimit);
    }
}
