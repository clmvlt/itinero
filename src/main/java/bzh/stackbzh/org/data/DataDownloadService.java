package bzh.stackbzh.org.data;

import bzh.stackbzh.org.status.StatusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

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

    public boolean download(String url, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
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
            String name = target.getFileName().toString();
            copyWithProgress(response.body(), tmp, total, name);

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            long size = Files.size(target);
            status.updateDownload(name, size, total > 0 ? total : size, true);
            log.info("Telechargement termine : {} ({} octets)", target, size);
            return true;
        } catch (DataDownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DataDownloadException("Echec du telechargement de " + url, e);
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
