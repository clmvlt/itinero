package bzh.stackbzh.org.data;

import bzh.stackbzh.org.data.DataUpdateReport.DatasetResult;
import bzh.stackbzh.org.data.DataUpdateReport.Outcome;
import bzh.stackbzh.org.data.DataUpdateReport.Snapshot;
import bzh.stackbzh.org.notification.DiscordNotifier;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Le compte rendu Discord doit dire QUI a fait la mise a jour et CE QUI a change (versions, tailles, deltas, durees). */
class DataUpdateReportTest {

    private static final String OSM_URL = "https://download.geofabrik.de/europe/france-latest.osm.pbf";
    private static final String BAN_URL = "https://adresse.data.gouv.fr/data/ban/adresses/latest/csv/adresses-france.csv.gz";

    private static Map<String, Long> metrics(Object... keyValues) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], (Long) keyValues[i + 1]);
        }
        return m;
    }

    private static DataUpdateReport report(UpdateTrigger trigger) {
        return new DataUpdateReport(trigger, Instant.parse("2026-09-13T06:00:00Z"), "srv-ors-01", "itinero", 4242);
    }

    private static DatasetResult banUpToDate() {
        Snapshot ban = new Snapshot(940_391_474L, Instant.parse("2026-09-10T02:33:11Z"), metrics("Adresses", 26_123_456L));
        return new DatasetResult("BAN", "Adresses (BAN)", "", BAN_URL, null, Outcome.UP_TO_DATE, ban, ban, null, null, null);
    }

    @Test
    void rapportDeMiseAJourIndiqueQuiEtQuoi() {
        DataUpdateReport r = report(UpdateTrigger.SCHEDULED);
        Snapshot before = new Snapshot(5_024_490_959L, Instant.parse("2026-09-03T03:17:11Z"),
                metrics("Noeuds", 12_000_000L, "Aretes", 25_000_000L));
        Snapshot after = new Snapshot(5_078_011_689L, Instant.parse("2026-09-10T03:17:11Z"),
                metrics("Noeuds", 12_050_000L, "Aretes", 24_990_000L));
        r.add(new DatasetResult("OSM", "Reseau routier (OSM)", "", OSM_URL, "france-260909.osm.pbf", Outcome.UPDATED,
                before, after, Duration.ofMinutes(12).plusSeconds(3), Duration.ofMinutes(28), null));
        r.add(banUpToDate());
        r.finish(Instant.parse("2026-09-13T06:40:03Z"));

        assertEquals("SUCCESS", r.overallState());
        assertEquals(DiscordNotifier.Level.SUCCESS, r.level());
        assertEquals("Mise a jour des donnees - Planificateur hebdomadaire", r.title());
        assertEquals("OSM : mis a jour (version du 10/09/2026) - BAN : deja a jour (version du 10/09/2026)",
                r.summaryLine());

        String msg = r.toDiscordMessage();
        // Qui
        assertTrue(msg.contains("**Declencheur** : Planificateur hebdomadaire"), msg);
        assertTrue(msg.contains("srv-ors-01 - utilisateur `itinero` - PID 4242"), msg);
        assertTrue(msg.contains("**Debut** : 13/09/2026 08:00 (Europe/Paris) - **duree totale** : 40 min 3 s"), msg);
        // Quoi
        assertTrue(msg.contains("Version publiee : 03/09/2026 05:17 → 10/09/2026 05:17"), msg);
        assertTrue(msg.contains("Fichier distant : france-260909.osm.pbf"), msg);
        assertTrue(msg.contains("Fichier : 4,68 Go → 4,73 Go (+51,0 Mo)"), msg);
        assertTrue(msg.contains("Noeuds : 12 000 000 → 12 050 000 (+50 000)"), msg);
        assertTrue(msg.contains("Aretes : 25 000 000 → 24 990 000 (-10 000)"), msg);
        assertTrue(msg.contains("Durees : telechargement 12 min 3 s - reconstruction 28 min"), msg);
        assertTrue(msg.contains("Adresses : 26 123 456"), msg);
        assertTrue(msg.contains("Source : " + OSM_URL), msg);
    }

    @Test
    void echecPartielEtTotal() {
        Snapshot local = new Snapshot(100, Instant.parse("2026-09-03T03:17:11Z"), Map.of());
        DataUpdateReport partial = report(UpdateTrigger.STARTUP);
        partial.add(new DatasetResult("OSM", "Reseau routier (OSM)", "", OSM_URL, null, Outcome.FAILED,
                local, local, null, null, "Telechargement echoue : HTTP 503"));
        partial.add(banUpToDate());
        assertEquals("PARTIAL", partial.overallState());
        assertEquals(DiscordNotifier.Level.WARNING, partial.level());
        String msg = partial.toDiscordMessage();
        assertTrue(msg.contains("Erreur : Telechargement echoue : HTTP 503"), msg);
        assertTrue(msg.contains("Version locale conservee : 03/09/2026 05:17"), msg);
        assertTrue(partial.summaryLine().startsWith("OSM : echec (Telechargement echoue : HTTP 503)"));

        DataUpdateReport failed = report(UpdateTrigger.STARTUP);
        failed.add(new DatasetResult("OSM", "Reseau routier (OSM)", "", OSM_URL, null, Outcome.FAILED,
                local, local, null, null, "x"));
        failed.add(new DatasetResult("BAN", "Adresses (BAN)", "", BAN_URL, null, Outcome.FAILED,
                local, local, null, null, "y"));
        assertEquals("FAILED", failed.overallState());
        assertEquals(DiscordNotifier.Level.ERROR, failed.level());
    }

    @Test
    void rienAFaireEstInformatif() {
        DataUpdateReport r = report(UpdateTrigger.STARTUP);
        r.add(banUpToDate());
        assertEquals("UP_TO_DATE", r.overallState());
        assertEquals(DiscordNotifier.Level.INFO, r.level());
        assertTrue(r.toDiscordMessage().contains("**Declencheur** : Demarrage de l'API"));
    }

    @Test
    void telechargementInitialSansEtatPrecedent() {
        DataUpdateReport r = report(UpdateTrigger.STARTUP);
        Snapshot after = new Snapshot(940_391_474L, Instant.parse("2026-09-10T02:33:11Z"), metrics("Adresses", 26_123_456L));
        r.add(new DatasetResult("BAN", "Adresses (BAN)", "", BAN_URL, null, Outcome.INSTALLED,
                Snapshot.empty(), after, Duration.ofMinutes(3), null, "Fichier absent au demarrage"));
        assertEquals("SUCCESS", r.overallState());
        String msg = r.toDiscordMessage();
        assertTrue(msg.contains("telechargement initial"), msg);
        assertTrue(msg.contains("Version publiee : inconnue → 10/09/2026 04:33"), msg);
        assertTrue(msg.contains("Fichier : 896,8 Mo"), msg);
        assertTrue(msg.contains("Adresses : 26 123 456"), msg);
        assertTrue(msg.contains("Note : Fichier absent au demarrage"), msg);
    }

    @Test
    void formats() {
        assertEquals("4,73 Go", DataUpdateReport.formatBytes(5_078_011_689L));
        assertEquals("512 o", DataUpdateReport.formatBytes(512));
        assertEquals("?", DataUpdateReport.formatBytes(-1));
        assertEquals("1 h 02 min 05 s", DataUpdateReport.formatDuration(Duration.ofSeconds(3725)));
        assertEquals("28 min", DataUpdateReport.formatDuration(Duration.ofMinutes(28)));
        assertEquals("45 s", DataUpdateReport.formatDuration(Duration.ofSeconds(45)));
        assertEquals("1 234 567", DataUpdateReport.formatCount(1_234_567));
        assertEquals("inconnue", DataUpdateReport.formatDateTime(null));
    }
}
