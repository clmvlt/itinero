package bzh.stackbzh.org.data;

import bzh.stackbzh.org.data.DataDownloadService.DownloadResult;
import bzh.stackbzh.org.data.DataDownloadService.RemoteInfo;
import bzh.stackbzh.org.data.DataUpdateReport.DatasetResult;
import bzh.stackbzh.org.data.DataUpdateReport.Outcome;
import bzh.stackbzh.org.data.DataUpdateReport.Snapshot;
import bzh.stackbzh.org.geocoding.AddressSearchService;
import bzh.stackbzh.org.notification.DiscordNotifier;
import bzh.stackbzh.org.routing.RoutingEngine;
import bzh.stackbzh.org.status.ComponentState;
import bzh.stackbzh.org.status.StatusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Mise a jour des donnees (reseau routier OSM + adresses BAN), pipeline COMMUN au demarrage
 * ({@link UpdateTrigger#STARTUP}, via {@code StartupOrchestrator}) et au cron hebdomadaire
 * ({@link UpdateTrigger#SCHEDULED}, via {@link DataUpdateScheduler}).
 *
 * <p>Pour chaque jeu de donnees, dans l'ordre OSM puis BAN (RAM maitrisee) :
 * <ol>
 *   <li>HEAD sur l'URL source et comparaison avec la version locale ({@link DataDownloadService#isUpToDate}) ;
 *       deja a jour -> rien n'est telecharge ;</li>
 *   <li>sinon telechargement (le moteur continue de servir l'ancienne version pendant ce temps) ;</li>
 *   <li>reconstruction + bascule a chaud ({@code RoutingEngine.reload(true)} / {@code AddressSearchService.reload()}).</li>
 * </ol>
 * Un compte rendu ({@link DataUpdateReport}) est publie sur Discord (qui a declenche, sur quelle machine,
 * ce qui a change : versions, tailles, noeuds/aretes/adresses, durees), logge et resume dans {@code GET /status}.
 * Une seule execution a la fois : un declenchement concurrent est ignore (log + notification).
 */
@Service
public class DataUpdateService {

    private static final Logger log = LoggerFactory.getLogger(DataUpdateService.class);
    private static final String EMOJI_MAP = "🗺️";
    private static final String EMOJI_PIN = "📍";

    private record Dataset(String shortLabel, String label, String emoji, String url, Path file, String component,
                           Supplier<Map<String, Long>> metrics, Runnable rebuild, BooleanSupplier ready) {
    }

    /** Decision prise apres la verification : soit un resultat deja acquis, soit une version distante a telecharger. */
    private record Plan(Dataset dataset, Snapshot before, RemoteInfo remote, DatasetResult resolved) {
        boolean needsDownload() {
            return remote != null;
        }
    }

    private final boolean autoDownload;
    private final boolean updateOnStart;
    private final Dataset osm;
    private final Dataset ban;
    private final DataDownloadService downloadService;
    private final RoutingEngine routingEngine;
    private final AddressSearchService addressSearchService;
    private final StatusRegistry status;
    private final DiscordNotifier notifier;
    private final AtomicBoolean running = new AtomicBoolean();
    private final String host = hostName();
    private final String user = System.getProperty("user.name", "inconnu");
    private final long pid = ProcessHandle.current().pid();

    public DataUpdateService(
            @Value("${app.data.auto-download}") boolean autoDownload,
            @Value("${app.data.update-on-start:true}") boolean updateOnStart,
            @Value("${app.routing.osm-url}") String osmUrl,
            @Value("${app.routing.osm-file}") String osmFile,
            @Value("${app.geocoding.ban-url}") String banUrl,
            @Value("${app.geocoding.ban-file}") String banFile,
            DataDownloadService downloadService,
            RoutingEngine routingEngine,
            AddressSearchService addressSearchService,
            StatusRegistry status,
            DiscordNotifier notifier) {
        this.autoDownload = autoDownload;
        this.updateOnStart = updateOnStart;
        this.downloadService = downloadService;
        this.routingEngine = routingEngine;
        this.addressSearchService = addressSearchService;
        this.status = status;
        this.notifier = notifier;
        this.osm = new Dataset("OSM", "Reseau routier (OSM)", EMOJI_MAP, osmUrl, Path.of(osmFile),
                RoutingEngine.COMPONENT, this::graphMetrics, () -> routingEngine.reload(true), routingEngine::isReady);
        this.ban = new Dataset("BAN", "Adresses (BAN)", EMOJI_PIN, banUrl, Path.of(banFile),
                AddressSearchService.COMPONENT, this::addressMetrics, addressSearchService::reload,
                addressSearchService::isReady);
    }

    public boolean isUpdateOnStart() {
        return updateOnStart;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Appele par l'orchestrateur de demarrage une fois les moteurs charges. Les resultats des
     * telechargements initiaux ({@code DataBootstrap}, null si rien n'a ete telecharge) sont
     * integres au compte rendu au lieu de redeclencher une verification.
     */
    public void runOnStartup(DownloadResult osmInitial, DownloadResult banInitial) {
        if (!updateOnStart) {
            log.info("Verification des donnees au demarrage desactivee (app.data.update-on-start=false).");
            return;
        }
        run(UpdateTrigger.STARTUP, osmInitial, banInitial);
    }

    public void run(UpdateTrigger trigger) {
        run(trigger, null, null);
    }

    public void run(UpdateTrigger trigger, DownloadResult osmInitial, DownloadResult banInitial) {
        if (!autoDownload) {
            log.info("Mise a jour des donnees ({}) ignoree : app.data.auto-download=false.", trigger.label());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("Mise a jour des donnees ({}) ignoree : une mise a jour est deja en cours.", trigger.label());
            notifier.notify("Mise a jour des donnees ignoree - " + trigger.label(),
                    "**Declencheur** : " + trigger.label() + "\n**Machine** : " + machineLine()
                            + "\n\nUne autre mise a jour est deja en cours sur cette instance : cette execution est ignoree.",
                    DiscordNotifier.Level.WARNING);
            return;
        }
        try {
            doRun(trigger, osmInitial, banInitial);
        } catch (Exception e) {
            log.error("Mise a jour des donnees ({}) : erreur inattendue.", trigger.label(), e);
            long now = System.currentTimeMillis();
            String cause = DataDownloadService.describe(e);
            status.setDataUpdate(new StatusRegistry.DataUpdateInfo(trigger.label(), "FAILED", now, now,
                    "Erreur inattendue : " + cause));
            notifier.notifyError("Mise a jour des donnees en echec - " + trigger.label(),
                    "**Machine** : " + machineLine() + "\nErreur inattendue : " + cause);
        } finally {
            running.set(false);
        }
    }

    private void doRun(UpdateTrigger trigger, DownloadResult osmInitial, DownloadResult banInitial) {
        Instant startedAt = Instant.now();
        DataUpdateReport report = new DataUpdateReport(trigger, startedAt, host, user, pid);
        log.info("=== Mise a jour des donnees ({}) : debut ===", trigger.label());
        status.setDataUpdate(new StatusRegistry.DataUpdateInfo(trigger.label(), "RUNNING", startedAt.toEpochMilli(),
                null, "Verification des versions publiees..."));

        List<Plan> plans = List.of(plan(osm, osmInitial), plan(ban, banInitial));
        List<Plan> stale = plans.stream().filter(Plan::needsDownload).toList();
        if (!stale.isEmpty()) {
            notifyStart(trigger, stale);
            status.setDataUpdate(new StatusRegistry.DataUpdateInfo(trigger.label(), "RUNNING", startedAt.toEpochMilli(),
                    null, "Telechargement et reconstruction : "
                    + stale.stream().map(p -> p.dataset().shortLabel()).collect(Collectors.joining(", "))));
        }
        for (Plan plan : plans) {
            report.add(plan.resolved() != null ? plan.resolved() : apply(plan));
        }
        report.finish(Instant.now());

        status.setDataUpdate(new StatusRegistry.DataUpdateInfo(trigger.label(), report.overallState(),
                startedAt.toEpochMilli(), report.finishedAt().toEpochMilli(), report.summaryLine()));
        log.info("=== Mise a jour des donnees ({}) : terminee [{}] - {} ===\n{}", trigger.label(),
                report.overallState(), report.summaryLine(), report.toDiscordMessage());
        notifier.notify(report.title(), report.toDiscordMessage(), report.level());
    }

    private Plan plan(Dataset ds, DownloadResult initial) {
        Snapshot current = snapshot(ds);
        if (initial != null) {
            DatasetResult installed = new DatasetResult(ds.shortLabel(), ds.label(), ds.emoji(), ds.url(), null,
                    Outcome.INSTALLED, Snapshot.empty(), current, initial.duration(), null,
                    "Fichier absent au demarrage : telecharge puis construit par l'initialisation.");
            return new Plan(ds, current, null, installed);
        }
        RemoteInfo remote;
        try {
            remote = downloadService.head(ds.url());
        } catch (Exception e) {
            log.warn("{} : verification de la version publiee impossible.", ds.label(), e);
            return new Plan(ds, current, null, failed(ds, current, null, null,
                    "Verification de la version publiee impossible : " + DataDownloadService.describe(e)));
        }
        if (DataDownloadService.isUpToDate(ds.file(), remote)) {
            log.info("{} : deja a jour (version publiee le {}).", ds.label(),
                    DataUpdateReport.formatDateTime(remote.lastModified()));
            return new Plan(ds, current, null, new DatasetResult(ds.shortLabel(), ds.label(), ds.emoji(), ds.url(),
                    null, Outcome.UP_TO_DATE, current, current, null, null, null));
        }
        log.info("{} : nouvelle version publiee le {} ({}) - version locale : {}.", ds.label(),
                DataUpdateReport.formatDateTime(remote.lastModified()),
                DataUpdateReport.formatBytes(remote.contentLength()),
                DataUpdateReport.formatDateTime(current.version()));
        return new Plan(ds, current, remote, null);
    }

    private DatasetResult apply(Plan plan) {
        Dataset ds = plan.dataset();
        markDownloading(ds);
        DownloadResult download;
        try {
            download = downloadService.download(ds.url(), ds.file());
        } catch (Exception e) {
            log.error("{} : telechargement echoue.", ds.label(), e);
            restoreState(ds, "telechargement de la nouvelle version echoue");
            return failed(ds, plan.before(), null, null,
                    "Telechargement echoue : " + DataDownloadService.describe(e));
        }
        log.info("{} : reconstruction a partir de la nouvelle version...", ds.label());
        Instant rebuildStart = Instant.now();
        String rebuildError = null;
        try {
            ds.rebuild().run();
        } catch (Exception e) {
            log.error("{} : reconstruction echouee.", ds.label(), e);
            rebuildError = DataDownloadService.describe(e);
        }
        Duration rebuildDuration = Duration.between(rebuildStart, Instant.now());
        if (!ds.ready().getAsBoolean()) {
            return failed(ds, plan.before(), download.duration(), rebuildDuration,
                    "Reconstruction echouee apres telechargement : moteur indisponible"
                            + (rebuildError != null ? " (" + rebuildError + ")" : " (voir les logs du serveur)"));
        }
        Snapshot after = snapshot(ds);
        String remoteName = plan.remote().resolvedFileName();
        if (remoteName.equals(ds.file().getFileName().toString())) {
            remoteName = null;
        }
        return new DatasetResult(ds.shortLabel(), ds.label(), ds.emoji(), ds.url(), remoteName, Outcome.UPDATED,
                plan.before(), after, download.duration(), rebuildDuration, null);
    }

    private static DatasetResult failed(Dataset ds, Snapshot before, Duration downloadDuration,
                                        Duration rebuildDuration, String message) {
        return new DatasetResult(ds.shortLabel(), ds.label(), ds.emoji(), ds.url(), null, Outcome.FAILED,
                before, before, downloadDuration, rebuildDuration, message);
    }

    private Snapshot snapshot(Dataset ds) {
        return new Snapshot(DataDownloadService.sizeQuietly(ds.file()),
                DataDownloadService.localVersion(ds.file()), ds.metrics().get());
    }

    /** Pendant le telechargement le moteur reste utilisable : il garde READY (detail explicite) s'il l'est. */
    private void markDownloading(Dataset ds) {
        boolean ready = ds.ready().getAsBoolean();
        status.setComponent(ds.component(), ready ? ComponentState.READY : ComponentState.DOWNLOADING,
                (ready ? "Pret (ancienne version) - " : "") + "mise a jour : telechargement de la nouvelle version...");
    }

    private void restoreState(Dataset ds, String reason) {
        if (ds.ready().getAsBoolean()) {
            status.setComponent(ds.component(), ComponentState.READY, "Pret - ancienne version conservee (" + reason + ").");
        } else {
            status.setComponent(ds.component(), ComponentState.ERROR, "Indisponible - " + reason + ".");
        }
    }

    private void notifyStart(UpdateTrigger trigger, List<Plan> stale) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Declencheur** : ").append(trigger.label()).append('\n');
        sb.append("**Machine** : ").append(machineLine()).append("\n\n");
        sb.append("Nouvelle version publiee pour :\n");
        for (Plan p : stale) {
            sb.append(p.dataset().emoji()).append(" **").append(p.dataset().label()).append("** : publiee le ")
                    .append(DataUpdateReport.formatDateTime(p.remote().lastModified()))
                    .append(" (").append(DataUpdateReport.formatBytes(p.remote().contentLength())).append(')')
                    .append(" - version locale : ").append(DataUpdateReport.formatDateTime(p.before().version()))
                    .append('\n');
        }
        sb.append("\nTelechargement puis reconstruction en cours ; le compte rendu detaille suivra.");
        if (stale.stream().anyMatch(p -> p.dataset() == osm)) {
            sb.append("\n⚠️ Le routing et l'optimisation repondront 503 pendant la reconstruction du graphe.");
        }
        if (stale.stream().anyMatch(p -> p.dataset() == ban)) {
            sb.append("\nLe geocoding reste disponible pendant la reconstruction de l'index.");
        }
        notifier.notify("Mise a jour des donnees : debut - " + trigger.label(), sb.toString(),
                DiscordNotifier.Level.INFO);
    }

    private Map<String, Long> graphMetrics() {
        RoutingEngine.GraphStats stats = routingEngine.graphStats();
        if (stats == null) {
            return Map.of();
        }
        Map<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("Noeuds", (long) stats.nodes());
        metrics.put("Aretes", (long) stats.edges());
        return metrics;
    }

    private Map<String, Long> addressMetrics() {
        int count = addressSearchService.documentCount();
        return count < 0 ? Map.of() : Map.of("Adresses", (long) count);
    }

    private String machineLine() {
        return host + " - utilisateur `" + user + "` - PID " + pid;
    }

    private static String hostName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            name = System.getenv("HOSTNAME");
        }
        if (name == null || name.isBlank()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                name = "hote-inconnu";
            }
        }
        return name;
    }
}
