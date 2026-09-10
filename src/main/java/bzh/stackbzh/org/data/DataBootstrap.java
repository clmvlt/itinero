package bzh.stackbzh.org.data;

import bzh.stackbzh.org.data.DataDownloadService.DownloadResult;
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

/**
 * Telechargement INITIAL des fichiers de donnees au demarrage, uniquement si ni le fichier source
 * ni son cache derive n'existent. Les mises a jour ulterieures (nouvelle version publiee) sont du
 * ressort de {@link DataUpdateService}, qui recoit le resultat de ces telechargements initiaux
 * pour les mentionner dans son compte rendu.
 */
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

    /** @return le resultat du telechargement initial, ou null si rien n'a ete telecharge (present, desactive ou echec). */
    public DownloadResult ensureOsm() {
        if (!autoDownload) {
            return null;
        }
        if (!Files.exists(Path.of(osmFile)) && isEmptyOrMissing(Path.of(graphCache))) {
            log.info("Fichier OSM manquant : telechargement (~5 Go, cela peut etre long)...");
            status.setComponent(RoutingEngine.COMPONENT, ComponentState.DOWNLOADING,
                    "Telechargement du reseau routier (~5 Go)...");
            return safeDownload(osmUrl, Path.of(osmFile));
        }
        return null;
    }

    /** @return le resultat du telechargement initial, ou null si rien n'a ete telecharge (present, desactive ou echec). */
    public DownloadResult ensureBan() {
        if (!autoDownload) {
            return null;
        }
        if (!Files.exists(Path.of(banFile)) && isEmptyOrMissing(Path.of(indexDir))) {
            log.info("Fichier BAN manquant : telechargement (~900 Mo)...");
            status.setComponent(AddressSearchService.COMPONENT, ComponentState.DOWNLOADING,
                    "Telechargement des adresses BAN (~900 Mo)...");
            return safeDownload(banUrl, Path.of(banFile));
        }
        return null;
    }

    private DownloadResult safeDownload(String url, Path target) {
        try {
            return downloadService.download(url, target);
        } catch (Exception e) {
            log.error("Telechargement automatique echoue pour {}.", url, e);
            return null;
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
