package bzh.stackbzh.org.data;

import bzh.stackbzh.org.geocoding.AddressSearchService;
import bzh.stackbzh.org.routing.RoutingEngine;
import bzh.stackbzh.org.status.ComponentState;
import bzh.stackbzh.org.status.StatusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class DataUpdateScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataUpdateScheduler.class);

    private final boolean autoDownload;
    private final String osmUrl;
    private final String osmFile;
    private final String banUrl;
    private final String banFile;
    private final DataDownloadService downloadService;
    private final RoutingEngine routingEngine;
    private final AddressSearchService addressSearchService;
    private final StatusRegistry status;

    public DataUpdateScheduler(
            @Value("${app.data.auto-download}") boolean autoDownload,
            @Value("${app.routing.osm-url}") String osmUrl,
            @Value("${app.routing.osm-file}") String osmFile,
            @Value("${app.geocoding.ban-url}") String banUrl,
            @Value("${app.geocoding.ban-file}") String banFile,
            DataDownloadService downloadService,
            RoutingEngine routingEngine,
            AddressSearchService addressSearchService,
            StatusRegistry status) {
        this.autoDownload = autoDownload;
        this.osmUrl = osmUrl;
        this.osmFile = osmFile;
        this.banUrl = banUrl;
        this.banFile = banFile;
        this.downloadService = downloadService;
        this.routingEngine = routingEngine;
        this.addressSearchService = addressSearchService;
        this.status = status;
    }

    @Scheduled(cron = "${app.data.update-cron}", zone = "Europe/Paris")
    public void updateData() {
        if (!autoDownload) {
            log.info("Mise a jour planifiee ignoree (app.data.auto-download=false).");
            return;
        }
        log.info("=== Mise a jour hebdomadaire des donnees : debut ===");

        try {
            status.setComponent(RoutingEngine.COMPONENT, ComponentState.DOWNLOADING,
                    "MAJ hebdo : telechargement du reseau routier...");
            downloadService.download(osmUrl, Path.of(osmFile));
            log.info("Reconstruction du graphe routier...");
            routingEngine.reload(true);
        } catch (Exception e) {
            log.error("Mise a jour du routing echouee.", e);
            status.setComponent(RoutingEngine.COMPONENT, ComponentState.ERROR, "MAJ echouee : " + e.getMessage());
        }

        try {
            status.setComponent(AddressSearchService.COMPONENT, ComponentState.DOWNLOADING,
                    "MAJ hebdo : telechargement des adresses...");
            downloadService.download(banUrl, Path.of(banFile));
            log.info("Reconstruction de l'index d'adresses...");
            addressSearchService.reload();
        } catch (Exception e) {
            log.error("Mise a jour des adresses echouee.", e);
            status.setComponent(AddressSearchService.COMPONENT, ComponentState.ERROR, "MAJ echouee : " + e.getMessage());
        }

        log.info("=== Mise a jour hebdomadaire des donnees : terminee ===");
    }
}
