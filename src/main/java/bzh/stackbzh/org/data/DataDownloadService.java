package bzh.stackbzh.org.data;

import bzh.stackbzh.org.status.StatusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Telechargement des fichiers de donnees (OSM, BAN) et verification de leur fraicheur.
 *
 * <p>Convention de version : apres un telechargement, la date de modification (mtime) du fichier
 * local est alignee sur l'en-tete HTTP {@code Last-Modified} du serveur. Le fichier porte ainsi sa
 * <b>date de publication</b>, ce qui permet a {@link #isUpToDate(Path, RemoteInfo)} de comparer
 * local/distant avec une simple requete HEAD, sans fichier de metadonnees ni re-telechargement.
 * Un fichier depose a la main (mtime = date de copie) est considere comme a jour jusqu'a la
 * prochaine publication distante.
 */
@Service
public class DataDownloadService {

    private static final Logger log = LoggerFactory.getLogger(DataDownloadService.class);
    private static final long LOG_EVERY_BYTES = 100L * 1024 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final StatusRegistry status;

    public DataDownloadService(StatusRegistry status) {
        this.status = status;
    }

    /** Metadonnees d'un fichier distant obtenues par une requete HEAD (rien n'est telecharge). */
    public record RemoteInfo(String url, String resolvedUrl, Instant lastModified, String etag, long contentLength) {

        /** Nom du fichier reellement servi apres redirections (ex. {@code france-260909.osm.pbf} chez Geofabrik). */
        public String resolvedFileName() {
            String u = resolvedUrl != null ? resolvedUrl : url;
            int q = u.indexOf('?');
            if (q >= 0) {
                u = u.substring(0, q);
            }
            int slash = u.lastIndexOf('/');
            return slash >= 0 ? u.substring(slash + 1) : u;
        }
    }

    /** Resultat d'un telechargement termine avec succes. */
    public record DownloadResult(Path target, long bytes, Instant remoteLastModified, Duration duration) {
    }

    /** Interroge le serveur (HEAD, redirections suivies) sans telecharger : date de publication, ETag, taille. */
    public RemoteInfo head(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new DataDownloadException("Statut HTTP inattendu " + response.statusCode() + " pour HEAD " + url);
            }
            HttpHeaders headers = response.headers();
            return new RemoteInfo(url, response.uri().toString(),
                    headers.firstValue("Last-Modified").map(DataDownloadService::parseHttpDate).orElse(null),
                    headers.firstValue("ETag").orElse(null),
                    headers.firstValueAsLong("Content-Length").orElse(-1));
        } catch (DataDownloadException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataDownloadException("Verification interrompue pour " + url, e);
        } catch (Exception e) {
            throw new DataDownloadException("Verification impossible (HEAD " + url + ") : " + e.getMessage(), e);
        }
    }

    public DownloadResult download(String url, Path target) {
        Instant start = Instant.now();
        String name = target.getFileName().toString();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(name + ".part");
            Files.deleteIfExists(tmp);

            log.info("Telechargement {} -> {}", url, target);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofHours(2))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new DataDownloadException("Statut HTTP inattendu " + response.statusCode() + " pour " + url);
            }

            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            Instant remoteLastModified = response.headers().firstValue("Last-Modified")
                    .map(DataDownloadService::parseHttpDate).orElse(null);
            copyWithProgress(response.body(), tmp, total, name);

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            stampVersion(target, remoteLastModified);
            long size = Files.size(target);
            status.updateDownload(name, size, total > 0 ? total : size, true);
            log.info("Telechargement termine : {} ({} octets, version publiee le {})", target, size, remoteLastModified);
            return new DownloadResult(target, size, remoteLastModified, Duration.between(start, Instant.now()));
        } catch (DataDownloadException e) {
            status.updateDownload(name, 0, 0, true);
            throw e;
        } catch (Exception e) {
            status.updateDownload(name, 0, 0, true);
            throw new DataDownloadException("Echec du telechargement de " + url, e);
        }
    }

    /**
     * Le fichier local est-il a jour par rapport a la version publiee ? Comparaison des dates de
     * publication (mtime local = Last-Modified du dernier telechargement) a la seconde pres ; si le
     * serveur n'annonce pas de date, on se rabat sur la taille annoncee. Fichier absent = pas a jour.
     */
    public static boolean isUpToDate(Path local, RemoteInfo remote) {
        if (!Files.isRegularFile(local)) {
            return false;
        }
        Instant localVersion = localVersion(local);
        if (remote.lastModified() != null && localVersion != null) {
            Instant remoteSeconds = remote.lastModified().truncatedTo(ChronoUnit.SECONDS);
            Instant localSeconds = localVersion.truncatedTo(ChronoUnit.SECONDS);
            return !remoteSeconds.isAfter(localSeconds);
        }
        return remote.contentLength() > 0 && remote.contentLength() == sizeQuietly(local);
    }

    /** Date de version du fichier local (= date de publication distante apres un telechargement), ou null. */
    public static Instant localVersion(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    /** Taille du fichier, -1 s'il est absent ou illisible. */
    public static long sizeQuietly(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    static Instant parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static void stampVersion(Path target, Instant remoteLastModified) {
        if (remoteLastModified == null) {
            return;
        }
        try {
            Files.setLastModifiedTime(target, FileTime.from(remoteLastModified));
        } catch (IOException e) {
            log.warn("Impossible d'aligner la date de {} sur la version publiee ({}).", target, remoteLastModified, e);
        }
    }

    private void copyWithProgress(InputStream in, Path tmp, long total, String name) throws Exception {
        byte[] buffer = new byte[1 << 20];
        long downloaded = 0;
        long nextLog = LOG_EVERY_BYTES;
        try (InputStream input = in; OutputStream out = Files.newOutputStream(tmp)) {
            int read;
            status.updateDownload(name, 0, total, false);
            long nextStatus = 0;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                if (downloaded >= nextStatus) {
                    status.updateDownload(name, downloaded, total, false);
                    nextStatus += 16L * 1024 * 1024;
                }
                if (downloaded >= nextLog) {
                    if (total > 0) {
                        log.info("  {} : {} / {} Mo ({}%)", name,
                                downloaded / (1024 * 1024), total / (1024 * 1024),
                                (downloaded * 100) / total);
                    } else {
                        log.info("  {} : {} Mo", name, downloaded / (1024 * 1024));
                    }
                    nextLog += LOG_EVERY_BYTES;
                }
            }
        }
    }

    public static class DataDownloadException extends RuntimeException {
        public DataDownloadException(String message) {
            super(message);
        }

        public DataDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
