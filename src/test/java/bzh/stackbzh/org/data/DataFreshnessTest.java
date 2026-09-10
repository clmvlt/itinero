package bzh.stackbzh.org.data;

import bzh.stackbzh.org.data.DataDownloadService.RemoteInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regle de fraicheur des donnees : le mtime du fichier local (= Last-Modified du dernier telechargement)
 * est compare a la date de publication annoncee par le serveur (HEAD), sans reseau ni Spring.
 */
class DataFreshnessTest {

    private static final Instant PUBLISHED = Instant.parse("2026-09-10T03:17:11Z");

    @TempDir
    Path dir;

    private Path localFile(int size, Instant version) throws IOException {
        Path file = dir.resolve("france-latest.osm.pbf");
        Files.write(file, new byte[size]);
        Files.setLastModifiedTime(file, FileTime.from(version));
        return file;
    }

    private static RemoteInfo remote(Instant lastModified, long length) {
        return new RemoteInfo("https://example.org/france-latest.osm.pbf",
                "https://example.org/france-260910.osm.pbf", lastModified, "\"etag\"", length);
    }

    @Test
    void fichierAbsentJamaisAJour() {
        assertFalse(DataDownloadService.isUpToDate(dir.resolve("absent.pbf"), remote(PUBLISHED, 10)));
    }

    @Test
    void memeDateDePublicationEstAJour() throws IOException {
        Path file = localFile(100, PUBLISHED);
        assertTrue(DataDownloadService.isUpToDate(file, remote(PUBLISHED, 100)));
        // Comparaison a la seconde (les dates HTTP n'ont pas de millisecondes).
        assertTrue(DataDownloadService.isUpToDate(file, remote(PUBLISHED.plusMillis(500), 100)));
    }

    @Test
    void publicationDistantePlusAnciennePasDeTelechargement() throws IOException {
        // Fichier depose a la main (mtime = date de copie, posterieure a la publication) : conserve.
        Path file = localFile(100, PUBLISHED);
        assertTrue(DataDownloadService.isUpToDate(file, remote(PUBLISHED.minusSeconds(3600), 999)));
    }

    @Test
    void publicationDistantePlusRecenteDeclencheLaMiseAJour() throws IOException {
        Path file = localFile(100, PUBLISHED);
        assertFalse(DataDownloadService.isUpToDate(file, remote(PUBLISHED.plusSeconds(1), 100)));
    }

    @Test
    void sansLastModifiedOnCompareLaTaille() throws IOException {
        Path file = localFile(100, PUBLISHED);
        assertTrue(DataDownloadService.isUpToDate(file, remote(null, 100)));
        assertFalse(DataDownloadService.isUpToDate(file, remote(null, 101)));
        assertFalse(DataDownloadService.isUpToDate(file, remote(null, -1)));
    }

    @Test
    void nomDuFichierDistantApresRedirection() {
        assertEquals("france-260910.osm.pbf", remote(PUBLISHED, 1).resolvedFileName());
        assertEquals("adresses-france.csv.gz", new RemoteInfo("https://x/adresses-france.csv.gz",
                "https://x/adresses-france.csv.gz?token=1", null, null, 1).resolvedFileName());
    }

    @Test
    void parseDateHttp() {
        assertEquals(PUBLISHED, DataDownloadService.parseHttpDate("Thu, 10 Sep 2026 03:17:11 GMT"));
        assertNull(DataDownloadService.parseHttpDate("n/a"));
    }
}
