package bzh.stackbzh.org.status;

import bzh.stackbzh.org.geocoding.AddressSearchService;
import bzh.stackbzh.org.routing.RoutingEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/status")
@Tag(name = "Statut", description = "Etat de l'application : sous-systemes, telechargements, memoire JVM, donnees.")
public class StatusController {

    private final StatusRegistry registry;
    private final RoutingEngine routingEngine;
    private final AddressSearchService addressSearchService;
    private final String osmFile;
    private final String graphCache;
    private final String banFile;
    private final String indexDir;

    public StatusController(StatusRegistry registry, RoutingEngine routingEngine,
                            AddressSearchService addressSearchService,
                            @Value("${app.routing.osm-file}") String osmFile,
                            @Value("${app.routing.graph-cache}") String graphCache,
                            @Value("${app.geocoding.ban-file}") String banFile,
                            @Value("${app.geocoding.index-dir}") String indexDir) {
        this.registry = registry;
        this.routingEngine = routingEngine;
        this.addressSearchService = addressSearchService;
        this.osmFile = osmFile;
        this.graphCache = graphCache;
        this.banFile = banFile;
        this.indexDir = indexDir;
    }

    @GetMapping
    @Operation(summary = "Etat complet de l'API",
            description = "Statut global, etat de chaque sous-systeme, telechargements en cours, "
                    + "memoire JVM, presence/taille des fichiers de donnees. Le tableau de bord '/' interroge cet endpoint.")
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
                jvm, components, downloads, data, links);
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

    public record StatusResponse(
            String application, String status, long uptimeSeconds, long startedAtMillis,
            String routingProfile, int addressCount,
            JvmDto jvm, List<ComponentDto> components, List<DownloadDto> downloads,
            List<DataFileDto> data, LinksDto links) {
    }

    public record ComponentDto(String name, String state, String detail, boolean ready) {
    }

    public record DownloadDto(String name, long downloadedBytes, long totalBytes, int percent, boolean done) {
    }

    public record DataFileDto(String name, String path, boolean present, long sizeBytes) {
    }

    public record JvmDto(String javaVersion, long pid, int cpuCores,
                         long memUsedBytes, long memMaxBytes, int memUsedPercent) {
    }

    public record LinksDto(String swaggerUi, String openApi) {
    }
}
