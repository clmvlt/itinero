package bzh.stackbzh.org.data;

import bzh.stackbzh.org.geocoding.AddressSearchService;
import bzh.stackbzh.org.routing.RoutingEngine;
import bzh.stackbzh.org.status.ComponentState;
import bzh.stackbzh.org.status.StatusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DataBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DataBootstrap.class);

    private final boolean autoDownload;
    private final String osmFile;
    private final String osmUrl;
    private final String graphCache;
    private final String banFile;
    private final String banUrl;
    private final String indexDir;
    private final DataDownloadService downloadService;
    private final StatusRegistry status;

    public DataBootstrap(
            @Value("${app.data.auto-download}") boolean autoDownload,
            @Value("${app.routing.osm-file}") String osmFile,
            @Value("${app.routing.osm-url}") String osmUrl,
            @Value("${app.routing.graph-cache}") String graphCache,
            @Value("${app.geocoding.ban-file}") String banFile,
            @Value("${app.geocoding.ban-url}") String banUrl,
            @Value("${app.geocoding.index-dir}") String indexDir,
            DataDownloadService downloadService,
            StatusRegistry status) {
        this.autoDownload = autoDownload;
        this.osmFile = osmFile;
        this.osmUrl = osmUrl;
        this.graphCache = graphCache;
        this.banFile = banFile;
        this.banUrl = banUrl;
        this.indexDir = indexDir;
        this.downloadService = downloadService;
        this.status = status;
    }

    public void ensureOsm() {
        if (!autoDownload) {
            return;
        }
        if (!Files.exists(Path.of(osmFile)) && isEmptyOrMissing(Path.of(graphCache))) {
            log.info("Fichier OSM manquant : telechargement (~5 Go, cela peut etre long)...");
            status.setComponent(RoutingEngine.COMPONENT, ComponentState.DOWNLOADING,
                    "Telechargement du reseau routier (~5 Go)...");
            safeDownload(osmUrl, Path.of(osmFile));
        }
    }

    public void ensureBan() {
        if (!autoDownload) {
            return;
        }
        if (!Files.exists(Path.of(banFile)) && isEmptyOrMissing(Path.of(indexDir))) {
            log.info("Fichier BAN manquant : telechargement (~900 Mo)...");
            status.setComponent(AddressSearchService.COMPONENT, ComponentState.DOWNLOADING,
                    "Telechargement des adresses BAN (~900 Mo)...");
            safeDownload(banUrl, Path.of(banFile));
        }
    }

    private void safeDownload(String url, Path target) {
        try {
            downloadService.download(url, target);
        } catch (Exception e) {
            log.error("Telechargement automatique echoue pour {}.", url, e);
        }
    }

    private static boolean isEmptyOrMissing(Path dir) {
        if (!Files.isDirectory(dir)) {
            return true;
        }
        String[] entries = dir.toFile().list();
        return entries == null || entries.length == 0;
    }
}
