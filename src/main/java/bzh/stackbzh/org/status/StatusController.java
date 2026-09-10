package bzh.stackbzh.org.status;

import bzh.stackbzh.org.data.DataUpdateReport;
import bzh.stackbzh.org.geocoding.AddressSearchService;
import bzh.stackbzh.org.routing.RoutingEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/status")
@Tag(name = "Statut", description = "Etat de l'application : sous-systemes, telechargements, memoire JVM, donnees, "
        + "mise a jour automatique des donnees (derniere execution, prochaine planifiee).")
public class StatusController {

    private final StatusRegistry registry;
    private final RoutingEngine routingEngine;
    private final AddressSearchService addressSearchService;
    private final String osmFile;
    private final String graphCache;
    private final String banFile;
    private final String indexDir;
    private final boolean autoDownload;
    private final boolean updateOnStart;
    private final String updateCron;

    public StatusController(StatusRegistry registry, RoutingEngine routingEngine,
                            AddressSearchService addressSearchService,
                            @Value("${app.routing.osm-file}") String osmFile,
                            @Value("${app.routing.graph-cache}") String graphCache,
                            @Value("${app.geocoding.ban-file}") String banFile,
                            @Value("${app.geocoding.index-dir}") String indexDir,
                            @Value("${app.data.auto-download}") boolean autoDownload,
                            @Value("${app.data.update-on-start:true}") boolean updateOnStart,
                            @Value("${app.data.update-cron}") String updateCron) {
        this.registry = registry;
        this.routingEngine = routingEngine;
        this.addressSearchService = addressSearchService;
        this.osmFile = osmFile;
        this.graphCache = graphCache;
        this.banFile = banFile;
        this.indexDir = indexDir;
        this.autoDownload = autoDownload;
        this.updateOnStart = updateOnStart;
        this.updateCron = updateCron;
    }

    @GetMapping
    @Operation(summary = "Etat complet de l'API",
            description = "Statut global, etat de chaque sous-systeme, telechargements en cours, memoire JVM, "
                    + "presence/taille des fichiers de donnees et objet `dataUpdate` (configuration de la mise a jour "
                    + "automatique des donnees, prochaine execution planifiee, derniere execution avec son declencheur, "
                    + "son etat et un resume par jeu de donnees). Le tableau de bord '/' interroge cet endpoint toutes "
                    + "les 2 s. Toujours 200, meme quand des briques sont indisponibles (voir `status` et `components`).")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Etat courant de l'API."))
    public StatusResponse status() {
        List<StatusRegistry.ComponentInfo> comps = registry.components();

        List<ComponentDto> components = comps.stream()
                .map(c -> new ComponentDto(c.name(), c.state().name(), c.detail(), c.state() == ComponentState.READY))
                .toList();

        List<DownloadDto> downloads = registry.downloads().stream()
                .map(d -> new DownloadDto(
                        d.name(), d.downloadedBytes(), d.totalBytes(),
                        d.totalBytes() > 0 ? (int) (d.downloadedBytes() * 100 / d.totalBytes()) : -1,
                        d.done()))
                .toList();

        List<DataFileDto> data = new ArrayList<>();
        data.add(dataFile("Reseau routier (OSM)", osmFile));
        data.add(dataDir("Cache du graphe", graphCache));
        data.add(dataFile("Adresses (BAN)", banFile));
        data.add(dataDir("Index d'adresses", indexDir));

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        JvmDto jvm = new JvmDto(
                System.getProperty("java.version"),
                ProcessHandle.current().pid(),
                rt.availableProcessors(),
                used,
                rt.maxMemory(),
                rt.maxMemory() > 0 ? (int) (used * 100 / rt.maxMemory()) : -1);

        long uptime = (System.currentTimeMillis() - registry.startedAtMillis()) / 1000;
        String overall = computeOverall(comps);

        LinksDto links = new LinksDto("/swagger-ui.html", "/v3/api-docs");

        return new StatusResponse("spring-org", overall, uptime, registry.startedAtMillis(),
                routingEngine.profileName(), addressSearchService.documentCount(),
                jvm, components, downloads, data, dataUpdate(), links);
    }

    private DataUpdateDto dataUpdate() {
        StatusRegistry.DataUpdateInfo last = registry.lastDataUpdate();
        LastDataUpdateDto lastDto = last == null ? null : new LastDataUpdateDto(
                last.trigger(), last.state(), last.startedAtMillis(), last.finishedAtMillis(), last.summary());
        return new DataUpdateDto(autoDownload, updateOnStart, updateCron, nextScheduledAtMillis(), lastDto);
    }

    private Long nextScheduledAtMillis() {
        if (!autoDownload) {
            return null;
        }
        try {
            ZonedDateTime next = CronExpression.parse(updateCron).next(ZonedDateTime.now(DataUpdateReport.ZONE));
            return next == null ? null : next.toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private static String computeOverall(List<StatusRegistry.ComponentInfo> comps) {
        boolean anyError = comps.stream().anyMatch(c -> c.state() == ComponentState.ERROR);
        if (anyError) {
            return "DEGRADED";
        }
        boolean anyLoading = comps.stream().anyMatch(c ->
                c.state() == ComponentState.WAITING
                        || c.state() == ComponentState.DOWNLOADING
                        || c.state() == ComponentState.INITIALIZING);
        return anyLoading ? "STARTING" : "UP";
    }

    private static DataFileDto dataFile(String label, String path) {
        Path p = Path.of(path);
        boolean present = Files.isRegularFile(p);
        long size = present ? sizeQuietly(p) : -1;
        return new DataFileDto(label, p.toString(), present, size);
    }

    private static DataFileDto dataDir(String label, String path) {
        Path p = Path.of(path);
        boolean present = Files.isDirectory(p) && p.toFile().list() != null && p.toFile().list().length > 0;
        return new DataFileDto(label, p.toString(), present, -1);
    }

    private static long sizeQuietly(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }

    @Schema(description = "Etat complet de l'API (reponse de GET /status).")
    public record StatusResponse(
            @Schema(description = "Nom de l'application.", example = "spring-org") String application,
            @Schema(description = "Etat global : UP (tout est pret), STARTING (chargement/telechargement en cours), "
                    + "DEGRADED (au moins un sous-systeme en erreur).", example = "UP",
                    allowableValues = {"UP", "STARTING", "DEGRADED"}) String status,
            @Schema(description = "Temps ecoule depuis le demarrage, en secondes.", example = "3600") long uptimeSeconds,
            @Schema(description = "Horodatage du demarrage (epoch millis).", example = "1757491200000") long startedAtMillis,
            @Schema(description = "Profil de routage GraphHopper actif.", example = "car") String routingProfile,
            @Schema(description = "Nombre d'adresses indexees (-1 si l'index n'est pas charge).", example = "26123456") int addressCount,
            JvmDto jvm,
            @Schema(description = "Etat de chaque sous-systeme (Routing, Geocoding).") List<ComponentDto> components,
            @Schema(description = "Telechargements connus depuis le demarrage (en cours ou termines).") List<DownloadDto> downloads,
            @Schema(description = "Presence et taille des fichiers/dossiers de donnees.") List<DataFileDto> data,
            @Schema(description = "Mise a jour automatique des donnees : configuration, prochaine execution, derniere execution.")
            DataUpdateDto dataUpdate,
            LinksDto links) {
    }

    @Schema(description = "Etat d'un sous-systeme.")
    public record ComponentDto(
            @Schema(description = "Nom du sous-systeme.", example = "Routing") String name,
            @Schema(description = "Etat.", example = "READY",
                    allowableValues = {"WAITING", "DOWNLOADING", "INITIALIZING", "READY", "DISABLED", "ERROR"}) String state,
            @Schema(description = "Detail lisible de l'etat courant.", example = "Graphe routier pret (profil car).") String detail,
            @Schema(description = "true si le sous-systeme repond aux requetes.", example = "true") boolean ready) {
    }

    @Schema(description = "Progression d'un telechargement de fichier de donnees.")
    public record DownloadDto(
            @Schema(description = "Nom du fichier.", example = "france-latest.osm.pbf") String name,
            @Schema(description = "Octets recus.", example = "1073741824") long downloadedBytes,
            @Schema(description = "Taille totale annoncee (-1 si inconnue).", example = "5078011689") long totalBytes,
            @Schema(description = "Pourcentage (-1 si taille inconnue).", example = "21") int percent,
            @Schema(description = "true une fois le telechargement termine (ou abandonne).", example = "false") boolean done) {
    }

    @Schema(description = "Fichier ou dossier de donnees.")
    public record DataFileDto(
            @Schema(description = "Libelle.", example = "Reseau routier (OSM)") String name,
            @Schema(description = "Chemin local.", example = "data/france-latest.osm.pbf") String path,
            @Schema(description = "Present sur le disque.", example = "true") boolean present,
            @Schema(description = "Taille en octets (-1 pour un dossier ou si absent).", example = "5024490959") long sizeBytes) {
    }

    @Schema(description = "Informations sur la JVM.")
    public record JvmDto(
            @Schema(example = "17.0.12") String javaVersion,
            @Schema(example = "12345") long pid,
            @Schema(example = "8") int cpuCores,
            @Schema(description = "Memoire heap utilisee (octets).", example = "4294967296") long memUsedBytes,
            @Schema(description = "Memoire heap maximale (octets).", example = "17179869184") long memMaxBytes,
            @Schema(description = "Pourcentage de heap utilise.", example = "25") int memUsedPercent) {
    }

    @Schema(description = "Liens utiles.")
    public record LinksDto(
            @Schema(example = "/swagger-ui.html") String swaggerUi,
            @Schema(example = "/v3/api-docs") String openApi) {
    }

    @Schema(description = "Mise a jour automatique des donnees (OSM + BAN) : configuration et historique. "
            + "Le pipeline est commun au demarrage et au cron : requete HEAD sur la source, comparaison de la date de "
            + "publication (`Last-Modified`) avec la version locale, puis telechargement + reconstruction + bascule a chaud "
            + "seulement si une version plus recente est publiee. Chaque execution est notifiee sur le webhook Discord.")
    public record DataUpdateDto(
            @Schema(description = "Telechargement/mise a jour automatiques actives (app.data.auto-download). false = aucune "
                    + "mise a jour, ni au demarrage ni par le cron.", example = "true") boolean autoDownload,
            @Schema(description = "Verification d'une nouvelle version au demarrage, une fois les moteurs charges "
                    + "(app.data.update-on-start).", example = "true") boolean updateOnStart,
            @Schema(description = "Expression cron Spring (6 champs, fuseau Europe/Paris) de la mise a jour planifiee.",
                    example = "0 0 8 * * SUN") String cron,
            @Schema(description = "Prochaine execution planifiee (epoch millis) ; null si auto-download=false ou cron invalide.",
                    example = "1757836800000", nullable = true) Long nextScheduledAtMillis,
            @Schema(description = "Derniere execution depuis le demarrage de l'API (null si aucune n'a encore eu lieu, "
                    + "par ex. pendant le chargement initial).", nullable = true) LastDataUpdateDto last) {
    }

    @Schema(description = "Derniere execution de la mise a jour des donnees.")
    public record LastDataUpdateDto(
            @Schema(description = "Qui a declenche la mise a jour.", example = "Demarrage de l'API",
                    allowableValues = {"Demarrage de l'API", "Planificateur hebdomadaire"}) String trigger,
            @Schema(description = "Etat : RUNNING (en cours), SUCCESS (au moins un jeu de donnees mis a jour ou installe, "
                    + "aucun echec), UP_TO_DATE (rien a faire : versions locales deja a jour), PARTIAL (un jeu de donnees "
                    + "en echec, l'autre OK), FAILED (tout en echec ou erreur inattendue).", example = "SUCCESS",
                    allowableValues = {"RUNNING", "SUCCESS", "UP_TO_DATE", "PARTIAL", "FAILED"}) String state,
            @Schema(description = "Debut de l'execution (epoch millis).", example = "1757491260000") long startedAtMillis,
            @Schema(description = "Fin de l'execution (epoch millis) ; null tant que state=RUNNING.",
                    example = "1757493780000", nullable = true) Long finishedAtMillis,
            @Schema(description = "Resume lisible par jeu de donnees (etape en cours si RUNNING).",
                    example = "OSM : mis a jour (version du 10/09/2026) - BAN : deja a jour (version du 10/09/2026)")
            String summary) {
    }
}
