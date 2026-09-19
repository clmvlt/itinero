package bzh.stackbzh.org.data;

import bzh.stackbzh.org.data.DataDownloadService.DataDownloadException;
import bzh.stackbzh.org.data.DataDownloadService.DownloadResult;
import bzh.stackbzh.org.status.StatusRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Robustesse du telechargement, sans Spring ni reseau externe : un serveur HTTP minimal (socket brut,
 * pour pouvoir <b>couper la connexion en plein corps de reponse</b> comme le fait un CDN qui lache)
 * rejoue les pannes constatees en production sur l'extrait OSM de 5 Go.
 *
 * <p>Ce que ces tests verrouillent :
 * <ul>
 *   <li>une coupure ne fait pas perdre les octets deja recus (reprise en {@code Range}) ;</li>
 *   <li>une erreur definitive (404) n'est pas reessayee cinq fois pour rien ;</li>
 *   <li>si la version publiee change en cours de route, le fichier repart de zero : jamais deux
 *       versions concatenees (un .pbf ainsi corrompu casserait la construction du graphe) ;</li>
 *   <li>le message d'erreur porte la cause reelle et les octets recus — c'est ce qui manquait au
 *       compte rendu Discord, qui disait seulement « Echec du telechargement de &lt;url&gt; ».</li>
 * </ul>
 */
class DataDownloadRetryTest {

    private static final int SIZE = 300_000;
    private static final byte[] V1 = payload(SIZE, 1);
    private static final byte[] V2 = payload(SIZE, 2);
    private static final String LAST_MODIFIED = DateTimeFormatter.RFC_1123_DATE_TIME
            .format(ZonedDateTime.of(2026, 9, 18, 4, 31, 0, 0, ZoneOffset.UTC));
    private static final Instant PUBLISHED = Instant.parse("2026-09-18T04:31:00Z");

    @TempDir
    Path dir;

    private FakeServer server;

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** Contenu deterministe : une concatenation de deux versions se verrait immediatement. */
    private static byte[] payload(int size, int version) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ((i * 31 + version * 7) & 0xFF);
        }
        return bytes;
    }

    /** Service configure pour des tests rapides : 3 tentatives, 1 s d'attente entre deux. */
    private DataDownloadService service() {
        return new DataDownloadService(new StatusRegistry(), 3, 1, 10);
    }

    @Test
    void uneCoupureEnPleinTransfertEstRepriseLaOuElleSestArretee() throws IOException {
        // 1re tentative : la connexion tombe a 40 % du fichier (cas Geofabrik constate en production).
        server = new FakeServer((request, out, attempt) ->
                sendFile(request, out, V1, "\"v1\"", attempt == 1 ? SIZE * 2 / 5 : -1));

        Path target = dir.resolve("file.bin");
        DownloadResult result = service().download(server.url(), target);

        assertEquals(SIZE, result.bytes());
        assertArrayEquals(V1, Files.readAllBytes(target), "le fichier reconstitue doit etre identique a la source");
        assertEquals(2, server.requests(), "la 2e requete doit reprendre le transfert, pas le recommencer");
        assertEquals("bytes=" + (SIZE * 2 / 5) + "-", server.lastRange(), "la reprise doit repartir de l'octet recu");
        assertEquals("\"v1\"", server.lastIfRange(), "la reprise doit etre protegee par If-Range");
        assertFalse(Files.exists(dir.resolve("file.bin.part")), "le fichier temporaire doit etre resorbe");
        // La convention de version (mtime = date de publication) reste respectee apres une reprise.
        assertEquals(PUBLISHED, result.remoteLastModified());
        assertEquals(PUBLISHED, DataDownloadService.localVersion(target));
    }

    @Test
    void unePublicationPendantLeTransfertFaitRepartirDeZero() throws IOException {
        // Le serveur republie entre les deux tentatives : If-Range ne correspond plus -> 200 complet.
        server = new FakeServer((request, out, attempt) -> {
            if (attempt == 1) {
                sendFile(request, out, V1, "\"v1\"", SIZE / 2);
            } else {
                sendFile(request, out, V2, "\"v2\"", -1);
            }
        });

        Path target = dir.resolve("file.bin");
        DownloadResult result = service().download(server.url(), target);

        assertEquals(SIZE, result.bytes());
        assertArrayEquals(V2, Files.readAllBytes(target),
                "le fichier doit etre la nouvelle version entiere, jamais un melange des deux");
    }

    @Test
    void uneErreurDefinitiveEchoueImmediatementSansReessayer() throws IOException {
        server = new FakeServer((request, out, attempt) ->
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII)));

        DataDownloadException error = assertThrows(DataDownloadException.class,
                () -> service().download(server.url(), dir.resolve("file.bin")));

        assertEquals(1, server.requests(), "un 404 ne devient pas un 200 en reessayant");
        assertTrue(error.getMessage().contains("404"), error.getMessage());
        assertTrue(error.getMessage().contains("1 tentative"), error.getMessage());
    }

    @Test
    void unEchecPersistantDitCombienDOctetsOntEteRecus() throws IOException {
        server = new FakeServer((request, out, attempt) -> sendFile(request, out, V1, "\"v1\"", SIZE / 10));

        DataDownloadException error = assertThrows(DataDownloadException.class,
                () -> service().download(server.url(), dir.resolve("file.bin")));

        assertEquals(3, server.requests(), "les 3 tentatives configurees doivent etre utilisees");
        assertTrue(error.getMessage().contains("3 tentative"), error.getMessage());
        assertTrue(error.getMessage().contains("recus sur"), error.getMessage());
        assertFalse(Files.exists(dir.resolve("file.bin.part")), "l'espace disque doit etre rendu apres l'echec");
        assertFalse(Files.exists(dir.resolve("file.bin")), "aucun fichier tronque ne doit etre mis en place");
    }

    @Test
    void leMessageDErreurRemonteLaChaineDesCauses() {
        // Le cas qui a motive la correction : une IOException nue enfouie sous une exception generique.
        Exception cause = new IOException("Connection reset");
        Exception wrapper = new IllegalStateException("lecture interrompue", cause);

        String described = DataDownloadService.describe(wrapper);

        assertTrue(described.contains("lecture interrompue"), described);
        assertTrue(described.contains("Connection reset"), described);
        assertEquals("cause inconnue", DataDownloadService.describe(null));
        // Une cause deja citee dans le message parent n'est pas repetee.
        assertEquals("DataDownloadException: echec : Connection reset",
                DataDownloadService.describe(new DataDownloadException("echec : Connection reset", cause)));
    }

    /**
     * Repond comme un serveur de fichiers : 206 + {@code Content-Range} si la requete porte un
     * {@code Range} dont le {@code If-Range} correspond encore, 200 complet sinon. En ecrivant moins
     * d'octets que le {@code Content-Length} annonce, on simule la coupure : le socket est ferme
     * juste apres par {@link FakeServer}.
     */
    private static void sendFile(Request request, OutputStream out, byte[] body, String etag, int bytesToWrite)
            throws IOException {
        String range = request.header("range");
        String ifRange = request.header("if-range");
        long from = 0;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && (ifRange == null || ifRange.equals(etag))) {
            from = Long.parseLong(range.substring("bytes=".length()).replace("-", ""));
            partial = true;
        }
        int remaining = (int) (body.length - from);
        StringBuilder head = new StringBuilder(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        head.append("Content-Length: ").append(remaining).append("\r\n");
        head.append("ETag: ").append(etag).append("\r\n");
        head.append("Last-Modified: ").append(LAST_MODIFIED).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        if (partial) {
            head.append("Content-Range: bytes ").append(from).append('-').append(body.length - 1)
                    .append('/').append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body, (int) from, Math.min(bytesToWrite < 0 ? remaining : bytesToWrite, remaining));
        out.flush();
    }

    /** Requete HTTP recue (ligne de requete + en-tetes, en minuscules pour la recherche). */
    private record Request(String line, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name);
        }
    }

    @FunctionalInterface
    private interface Responder {
        void respond(Request request, OutputStream out, int attempt) throws IOException;
    }

    /**
     * Serveur HTTP minimal sur la boucle locale. {@code com.sun.net.httpserver} ne convient pas ici :
     * il maintient la connexion ouverte apres une reponse tronquee, le client attend alors le chien
     * de garde au lieu de voir la coupure. Ici le socket est ferme des la reponse ecrite, ce qui
     * reproduit exactement une connexion lachee en plein transfert.
     */
    private static final class FakeServer implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread thread;
        private final AtomicInteger requests = new AtomicInteger();
        private volatile String lastRange;
        private volatile String lastIfRange;
        private volatile boolean closed;

        private FakeServer(Responder responder) throws IOException {
            this.socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            this.thread = new Thread(() -> serve(responder), "fake-file-server");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void serve(Responder responder) {
            while (!closed) {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(5000);
                    Request request = readRequest(client);
                    if (request == null) {
                        continue;
                    }
                    lastRange = request.header("range");
                    lastIfRange = request.header("if-range");
                    responder.respond(request, client.getOutputStream(), requests.incrementAndGet());
                } catch (IOException e) {
                    if (!closed) {
                        // Coupure volontaire ou client parti : la tentative suivante ouvrira une connexion.
                        continue;
                    }
                    return;
                }
            }
        }

        private static Request readRequest(Socket client) throws IOException {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return null;
            }
            Map<String, String> headers = new HashMap<>();
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) {
                    headers.put(header.substring(0, colon).trim().toLowerCase(), header.substring(colon + 1).trim());
                }
            }
            return new Request(line, headers);
        }

        private String url() {
            return "http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort() + "/file.bin";
        }

        private int requests() {
            return requests.get();
        }

        private String lastRange() {
            return lastRange;
        }

        private String lastIfRange() {
            return lastIfRange;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            socket.close();
        }
    }
}
