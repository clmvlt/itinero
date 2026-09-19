package bzh.stackbzh.org.data;

import bzh.stackbzh.org.status.StatusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Telechargement des fichiers de donnees (OSM, BAN) et verification de leur fraicheur.
 *
 * <p>Convention de version : apres un telechargement, la date de modification (mtime) du fichier
 * local est alignee sur l'en-tete HTTP {@code Last-Modified} du serveur. Le fichier porte ainsi sa
 * <b>date de publication</b>, ce qui permet a {@link #isUpToDate(Path, RemoteInfo)} de comparer
 * local/distant avec une simple requete HEAD, sans fichier de metadonnees ni re-telechargement.
 * Un fichier depose a la main (mtime = date de copie) est considere comme a jour jusqu'a la
 * prochaine publication distante.
 *
 * <h2>Robustesse des gros transferts (l'extrait OSM France fait ~5 Go)</h2>
 * Une coupure a 80 % d'un transfert de 5 Go faisait perdre la totalite du travail et laissait le
 * compte rendu sans explication. Un telechargement est donc decoupe en <b>tentatives</b> :
 * <ul>
 *   <li><b>reprise</b> : le fichier {@code .part} est conserve entre deux tentatives d'un meme appel
 *       et la suite est demandee en {@code Range: bytes=N-}, protegee par {@code If-Range}
 *       (ETag fort, sinon {@code Last-Modified}) — si la version publiee a change entre-temps, le
 *       serveur renvoie 200 et le fichier est repris depuis zero, jamais deux versions concatenees ;</li>
 *   <li><b>detection des blocages</b> : le timeout HTTP ne couvre pas la lecture du corps en mode
 *       flux ; un chien de garde ferme le flux si plus rien n'arrive pendant
 *       {@code app.data.download.stall-timeout-seconds}, ce qui transforme un blocage infini en
 *       tentative echouee (sinon la mise a jour restait « en cours » pour toujours, bloquant les
 *       suivantes via le garde {@code AtomicBoolean} de {@link DataUpdateService}) ;</li>
 *   <li><b>echec immediat</b> quand reessayer n'a aucun sens : 4xx definitif (404, 403...) ou espace
 *       disque insuffisant, verifie <i>avant</i> d'ecrire a partir de la taille annoncee ;</li>
 *   <li><b>verification de completude</b> : un transfert plus court que la taille annoncee est une
 *       erreur (fichier tronque) et non un succes silencieux ;</li>
 *   <li><b>messages parlants</b> : toute erreur est rendue via {@link #describe(Throwable)}, qui
 *       remonte la chaine des causes (une {@code IOException} reseau n'a souvent qu'un message nu).</li>
 * </ul>
 */
@Service
public class DataDownloadService {

    private static final Logger log = LoggerFactory.getLogger(DataDownloadService.class);
    private static final long LOG_EVERY_BYTES = 100L * 1024 * 1024;
    private static final long STATUS_EVERY_BYTES = 16L * 1024 * 1024;
    /** Marge exigee en plus de la taille annoncee : le disque ne doit pas finir a zero octet libre. */
    private static final long FREE_SPACE_MARGIN_BYTES = 256L * 1024 * 1024;
    /** Codes 4xx qui valent la peine d'etre reessayes (les autres sont definitifs). */
    private static final Set<Integer> RETRYABLE_CLIENT_ERRORS = Set.of(408, 423, 425, 429);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** Thread unique et demon : surveille les transferts bloques sans jamais retenir l'arret de la JVM. */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "data-download-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final StatusRegistry status;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Duration stallTimeout;

    public DataDownloadService(StatusRegistry status,
                               @Value("${app.data.download.max-attempts:5}") int maxAttempts,
                               @Value("${app.data.download.retry-delay-seconds:15}") long retryDelaySeconds,
                               @Value("${app.data.download.stall-timeout-seconds:180}") long stallTimeoutSeconds) {
        this.status = status;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelay = Duration.ofSeconds(Math.max(1, retryDelaySeconds));
        this.stallTimeout = Duration.ofSeconds(Math.max(10, stallTimeoutSeconds));
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

    /**
     * Interroge le serveur (HEAD, redirections suivies) sans telecharger : date de publication, ETag, taille.
     * Reessaye brievement : une coupure passagere ne doit pas faire passer un jeu de donnees pour
     * « verification impossible » jusqu'a la semaine suivante.
     */
    public RemoteInfo head(String url) {
        int attempts = Math.min(maxAttempts, 3);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                log.warn("Verification de {} : tentative {}/{} ({}).", url, attempt, attempts, describe(lastError));
                if (!pause(retryDelay)) {
                    break;
                }
            }
            try {
                return headOnce(url);
            } catch (DataDownloadException e) {
                if (e.isFatal()) {
                    throw e;
                }
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new DataDownloadException("Verification impossible (HEAD " + url + ")");
    }

    private RemoteInfo headOnce(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new DataDownloadException("Statut HTTP inattendu " + response.statusCode() + " pour HEAD " + url,
                        isFatalStatus(response.statusCode()));
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
            throw new DataDownloadException("Verification interrompue pour " + url, e, true);
        } catch (Exception e) {
            throw new DataDownloadException("Verification impossible (HEAD " + url + ") : " + describe(e), e);
        }
    }

    /**
     * Telecharge {@code url} vers {@code target}, avec reprise sur coupure (cf. javadoc de classe).
     * Le fichier n'est mis en place qu'une fois complet : l'ancienne version reste utilisable
     * pendant tout le transfert.
     *
     * @throws DataDownloadException si toutes les tentatives echouent ; le message porte la cause
     *                               reelle, le nombre de tentatives et les octets deja recus.
     */
    public DownloadResult download(String url, Path target) {
        Instant start = Instant.now();
        String name = target.getFileName().toString();
        Path tmp = target.resolveSibling(name + ".part");
        Path directory = target.toAbsolutePath().getParent();
        try {
            Files.createDirectories(directory);
            // Un reliquat d'un appel precedent n'est pas reprenable (on ignore de quelle version il vient).
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            status.updateDownload(name, 0, 0, true);
            throw new DataDownloadException("Preparation du telechargement impossible (" + tmp + ") : " + describe(e),
                    e, true);
        }

        log.info("Telechargement {} -> {}", url, target);
        Transfer transfer = new Transfer(url, tmp, directory, name);
        Exception lastError = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            if (attempt > 1) {
                Duration delay = retryDelay.multipliedBy(1L << Math.min(attempt - 2, 3));
                log.warn("Telechargement de {} : tentative {}/{} dans {} s ({} deja recus) - echec precedent : {}",
                        url, attempt, maxAttempts, delay.toSeconds(),
                        DataUpdateReport.formatBytes(transfer.received), describe(lastError));
                if (!pause(delay)) {
                    break;
                }
            }
            try {
                runAttempt(transfer, attempt);
                return finish(url, target, transfer, start);
            } catch (DataDownloadException e) {
                lastError = e;
                if (e.isFatal()) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = e;
                break;
            } catch (Exception e) {
                lastError = e;
            }
            transfer.received = Math.max(0, sizeQuietly(tmp));
        }

        status.updateDownload(name, transfer.received, transfer.total, true);
        // Le reliquat n'est pas reprenable au prochain appel : on rend l'espace disque (jusqu'a 5 Go).
        deleteQuietly(tmp);
        throw new DataDownloadException("Echec du telechargement de " + url + " apres " + attempt + " tentative(s)"
                + received(transfer) + " : " + describe(lastError), lastError);
    }

    /** Une tentative : requete (complete ou reprise), controles, puis ecriture dans le fichier {@code .part}. */
    private void runAttempt(Transfer transfer, int attempt) throws Exception {
        if (transfer.total > 0 && transfer.received == transfer.total) {
            // Le fichier etait complet et seule sa mise en place a echoue : inutile de reprendre le reseau.
            log.info("{} : fichier deja complet, nouvelle tentative de mise en place.", transfer.name);
            return;
        }
        boolean resuming = transfer.received > 0 && transfer.validator != null;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(transfer.url))
                .timeout(Duration.ofHours(2))
                // HTTP/1.1 impose : sur un transfert de plusieurs Go, HTTP/2 ajoute un controle de flux
                // et des GOAWAY dont on n'a aucun besoin pour un simple fichier.
                .version(HttpClient.Version.HTTP_1_1)
                .GET();
        if (resuming) {
            builder.header("Range", "bytes=" + transfer.received + "-").header("If-Range", transfer.validator);
        }

        HttpResponse<InputStream> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int code = response.statusCode();
        if (code == 416) {
            // Plage refusee (fichier local deja plus gros que la source) : on repart de zero.
            closeQuietly(response.body());
            transfer.received = 0;
            deleteQuietly(transfer.tmp);
            throw new DataDownloadException("Reprise refusee par le serveur (416) : reprise depuis le debut");
        }
        if (code != 200 && code != 206) {
            closeQuietly(response.body());
            throw new DataDownloadException("Statut HTTP inattendu " + code + " pour " + transfer.url,
                    isFatalStatus(code));
        }
        if (resuming && code == 200) {
            log.info("{} : la version publiee a change pendant le transfert, reprise depuis le debut.", transfer.name);
        }
        boolean append = code == 206;
        if (!append) {
            transfer.received = 0;
        }
        transfer.capture(response, append);
        ensureFreeSpace(transfer);
        if (attempt > 1) {
            log.info("{} : tentative {} - {} ({})", transfer.name, attempt,
                    append ? "reprise a " + DataUpdateReport.formatBytes(transfer.received) : "transfert complet",
                    DataUpdateReport.formatBytes(transfer.total));
        }
        copyWithProgress(response.body(), transfer, append);
        if (transfer.total > 0 && transfer.received != transfer.total) {
            throw new DataDownloadException("Transfert incomplet : " + DataUpdateReport.formatBytes(transfer.received)
                    + " recus sur " + DataUpdateReport.formatBytes(transfer.total));
        }
    }

    private DownloadResult finish(String url, Path target, Transfer transfer, Instant start) throws IOException {
        Files.move(transfer.tmp, target, StandardCopyOption.REPLACE_EXISTING);
        stampVersion(target, transfer.lastModified);
        long size = Files.size(target);
        status.updateDownload(transfer.name, size, size, true);
        Duration duration = Duration.between(start, Instant.now());
        log.info("Telechargement termine : {} ({} octets, version publiee le {}, en {})", target, size,
                transfer.lastModified, DataUpdateReport.formatDuration(duration));
        return new DownloadResult(target, size, transfer.lastModified, duration);
    }

    /**
     * Refuse d'ecrire s'il n'y a pas la place : sans ce controle l'echec arrive apres des minutes de
     * transfert, avec un {@code IOException} systeme peu clair, et le disque est rempli a ras bord.
     */
    private void ensureFreeSpace(Transfer transfer) {
        long remaining = transfer.total > 0 ? transfer.total - transfer.received : -1;
        if (remaining <= 0) {
            return;
        }
        long usable;
        try {
            FileStore store = Files.getFileStore(transfer.directory);
            usable = store.getUsableSpace();
        } catch (IOException e) {
            log.debug("Espace disque de {} non verifiable : {}", transfer.directory, describe(e));
            return;
        }
        if (usable >= 0 && usable < remaining + FREE_SPACE_MARGIN_BYTES) {
            throw new DataDownloadException("Espace disque insuffisant dans " + transfer.directory + " : "
                    + DataUpdateReport.formatBytes(usable) + " libres, "
                    + DataUpdateReport.formatBytes(remaining + FREE_SPACE_MARGIN_BYTES) + " necessaires ("
                    + DataUpdateReport.formatBytes(remaining) + " a telecharger + marge)", true);
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

    /**
     * Message lisible pour une exception, cause comprise. Indispensable au diagnostic a distance :
     * les erreurs reseau et disque arrivent souvent sous la forme d'une {@code IOException} au message
     * nu (« Connection reset ») emballee dans une exception plus generique. Sans ce deroulage, le
     * compte rendu Discord annoncait « Echec du telechargement de &lt;url&gt; » sans dire pourquoi.
     *
     * @return les causes successives separees par {@code <-}, sans repeter un message deja cite.
     */
    public static String describe(Throwable error) {
        if (error == null) {
            return "cause inconnue";
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++) {
            String message = current.getMessage();
            boolean alreadySaid = message != null && !message.isBlank() && sb.indexOf(message) >= 0;
            if (!alreadySaid) {
                if (sb.length() > 0) {
                    sb.append(" <- ");
                }
                sb.append(message == null || message.isBlank()
                        ? current.getClass().getSimpleName()
                        : current.getClass().getSimpleName() + ": " + message);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        String text = sb.toString().replace('\n', ' ');
        return text.length() > 400 ? text.substring(0, 397) + "..." : text;
    }

    static Instant parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** 4xx = requete definitivement mauvaise (sauf temporisations et quotas) ; 5xx = serveur, reessayable. */
    private static boolean isFatalStatus(int code) {
        return code >= 400 && code < 500 && !RETRYABLE_CLIENT_ERRORS.contains(code);
    }

    private static String received(Transfer transfer) {
        if (transfer.received <= 0) {
            return "";
        }
        return " (" + DataUpdateReport.formatBytes(transfer.received)
                + (transfer.total > 0 ? " recus sur " + DataUpdateReport.formatBytes(transfer.total) : " recus") + ")";
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

    private void copyWithProgress(InputStream in, Transfer transfer, boolean append) throws Exception {
        byte[] buffer = new byte[1 << 20];
        long nextLog = transfer.received + LOG_EVERY_BYTES;
        long nextStatus = transfer.received;
        AtomicLong lastProgressAt = new AtomicLong(System.nanoTime());
        ScheduledFuture<?> guard = watchStall(in, transfer, lastProgressAt);
        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
        try (InputStream input = in; OutputStream out = Files.newOutputStream(transfer.tmp, options)) {
            status.updateDownload(transfer.name, transfer.received, transfer.total, false);
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                transfer.received += read;
                lastProgressAt.set(System.nanoTime());
                if (transfer.received >= nextStatus) {
                    status.updateDownload(transfer.name, transfer.received, transfer.total, false);
                    nextStatus = transfer.received + STATUS_EVERY_BYTES;
                }
                if (transfer.received >= nextLog) {
                    if (transfer.total > 0) {
                        log.info("  {} : {} / {} Mo ({}%)", transfer.name,
                                transfer.received / (1024 * 1024), transfer.total / (1024 * 1024),
                                (transfer.received * 100) / transfer.total);
                    } else {
                        log.info("  {} : {} Mo", transfer.name, transfer.received / (1024 * 1024));
                    }
                    nextLog = transfer.received + LOG_EVERY_BYTES;
                }
            }
        } catch (IOException e) {
            if (transfer.stalled) {
                throw new DataDownloadException("Transfert bloque : aucune donnee recue pendant "
                        + stallTimeout.toSeconds() + " s", e);
            }
            throw e;
        } finally {
            guard.cancel(false);
        }
    }

    /**
     * Chien de garde : le timeout de {@link HttpRequest} ne couvre <b>pas</b> la lecture du corps en
     * mode flux, donc un serveur qui garde la connexion ouverte sans rien envoyer bloquerait
     * {@code read()} indefiniment. Fermer le flux depuis un autre thread fait echouer la lecture,
     * ce qui redonne la main a la boucle de tentatives.
     */
    private ScheduledFuture<?> watchStall(InputStream in, Transfer transfer, AtomicLong lastProgressAt) {
        long periodSeconds = Math.max(5, stallTimeout.toSeconds() / 4);
        return watchdog.scheduleAtFixedRate(() -> {
            long idleNanos = System.nanoTime() - lastProgressAt.get();
            if (idleNanos > stallTimeout.toNanos()) {
                transfer.stalled = true;
                log.warn("{} : aucune donnee recue depuis {} s, transfert interrompu.", transfer.name,
                        TimeUnit.NANOSECONDS.toSeconds(idleNanos));
                closeQuietly(in);
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /** @return false si l'attente a ete interrompue (arret de l'application). */
    private static boolean pause(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            log.debug("Fermeture du flux : {}", describe(e));
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Impossible de supprimer le fichier temporaire {} : {}", file, describe(e));
        }
    }

    /** Etat d'un telechargement, partage entre ses tentatives successives (permet la reprise). */
    private static final class Transfer {
        private final String url;
        private final Path tmp;
        private final Path directory;
        private final String name;
        /** Octets deja ecrits dans {@code .part}. */
        private long received;
        /** Taille totale attendue, -1 si le serveur ne l'annonce pas. */
        private long total = -1;
        /** Validateur pour {@code If-Range} : ETag fort, sinon date de publication. */
        private String validator;
        private Instant lastModified;
        private volatile boolean stalled;

        private Transfer(String url, Path tmp, Path directory, String name) {
            this.url = url;
            this.tmp = tmp;
            this.directory = directory;
            this.name = name;
        }

        /** Retient de quoi reprendre le transfert : taille totale et validateur de version. */
        private void capture(HttpResponse<InputStream> response, boolean partial) {
            HttpHeaders headers = response.headers();
            headers.firstValue("Last-Modified").map(DataDownloadService::parseHttpDate)
                    .ifPresent(instant -> lastModified = instant);
            if (validator == null) {
                // Un ETag faible (W/"...") n'a pas le droit de servir a If-Range : repli sur la date.
                String etag = headers.firstValue("ETag").filter(v -> !v.startsWith("W/")).orElse(null);
                validator = etag != null ? etag : headers.firstValue("Last-Modified").orElse(null);
            }
            long announced = partial
                    ? totalFromContentRange(headers.firstValue("Content-Range").orElse(null))
                    : headers.firstValueAsLong("Content-Length").orElse(-1);
            if (announced > 0) {
                total = announced;
            } else if (!partial) {
                total = -1;
            }
        }

        /** {@code bytes 1024-5119/5120} donne 5120 ; taille inconnue ou en-tete absent donne -1. */
        private static long totalFromContentRange(String header) {
            if (header == null) {
                return -1;
            }
            int slash = header.lastIndexOf('/');
            if (slash < 0) {
                return -1;
            }
            try {
                return Long.parseLong(header.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    public static class DataDownloadException extends RuntimeException {

        /** Vrai quand reessayer ne peut pas aider (404, espace disque, interruption). */
        private final boolean fatal;

        public DataDownloadException(String message) {
            this(message, null, false);
        }

        public DataDownloadException(String message, boolean fatal) {
            this(message, null, fatal);
        }

        public DataDownloadException(String message, Throwable cause) {
            this(message, cause, false);
        }

        public DataDownloadException(String message, Throwable cause, boolean fatal) {
            super(message, cause);
            this.fatal = fatal;
        }

        public boolean isFatal() {
            return fatal;
        }
    }
}
