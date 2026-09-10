package bzh.stackbzh.org.data;

import bzh.stackbzh.org.notification.DiscordNotifier;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compte rendu d'une mise a jour des donnees (OSM + BAN) : QUI l'a declenchee (declencheur,
 * machine, utilisateur systeme, PID) et CE QUI A CHANGE pour chaque jeu de donnees (date de
 * publication avant/apres, taille du fichier, noeuds/aretes du graphe ou adresses indexees avec
 * les deltas, durees de telechargement et de reconstruction). Sert a la notification Discord,
 * aux logs et au resume expose par {@code GET /status} ({@code dataUpdate.last}).
 */
public final class DataUpdateReport {

    public static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZONE);
    private static final String ARROW = " → ";

    public enum Outcome {
        /** Fichier absent au demarrage : telecharge par le bootstrap puis construit par l'initialisation. */
        INSTALLED("telechargement initial", "📥"),
        /** Nouvelle version publiee : telechargee, graphe/index reconstruit et bascule a chaud. */
        UPDATED("mis a jour", "✅"),
        /** La version locale est deja celle publiee : rien a faire. */
        UP_TO_DATE("deja a jour", "✔️"),
        /** Verification, telechargement ou reconstruction en echec (details dans {@code message}). */
        FAILED("echec", "❌");

        private final String label;
        private final String emoji;

        Outcome(String label, String emoji) {
            this.label = label;
            this.emoji = emoji;
        }

        public String label() {
            return label;
        }

        public String emoji() {
            return emoji;
        }
    }

    /**
     * Etat d'un jeu de donnees a un instant donne : taille du fichier source (-1 si absent), date de
     * version (= date de publication) et metriques du moteur (ex. {@code Noeuds}, {@code Aretes} ou
     * {@code Adresses}), vides si le moteur n'est pas charge.
     */
    public record Snapshot(long fileBytes, Instant version, Map<String, Long> metrics) {
        public static Snapshot empty() {
            return new Snapshot(-1, null, Map.of());
        }
    }

    public record DatasetResult(String shortLabel, String label, String emoji, String url, String remoteFileName,
                                Outcome outcome, Snapshot before, Snapshot after,
                                Duration downloadDuration, Duration rebuildDuration, String message) {
    }

    private final UpdateTrigger trigger;
    private final Instant startedAt;
    private final String host;
    private final String user;
    private final long pid;
    private final List<DatasetResult> datasets = new ArrayList<>();
    private Instant finishedAt;

    public DataUpdateReport(UpdateTrigger trigger, Instant startedAt, String host, String user, long pid) {
        this.trigger = trigger;
        this.startedAt = startedAt;
        this.host = host;
        this.user = user;
        this.pid = pid;
    }

    public void add(DatasetResult result) {
        datasets.add(result);
    }

    public void finish(Instant at) {
        this.finishedAt = at;
    }

    public UpdateTrigger trigger() {
        return trigger;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public List<DatasetResult> datasets() {
        return Collections.unmodifiableList(datasets);
    }

    public String title() {
        return "Mise a jour des donnees - " + trigger.label();
    }

    /** "hote - utilisateur `x` - PID n" : identifie l'instance qui a fait la mise a jour. */
    public String machineLine() {
        return host + " - utilisateur `" + user + "` - PID " + pid;
    }

    /**
     * Etat global : {@code SUCCESS} (au moins un jeu installe/mis a jour, aucun echec),
     * {@code UP_TO_DATE} (rien a faire), {@code PARTIAL} (un echec parmi d'autres), {@code FAILED} (tout en echec).
     */
    public String overallState() {
        long failed = datasets.stream().filter(d -> d.outcome() == Outcome.FAILED).count();
        if (!datasets.isEmpty() && failed == datasets.size()) {
            return "FAILED";
        }
        if (failed > 0) {
            return "PARTIAL";
        }
        boolean changed = datasets.stream()
                .anyMatch(d -> d.outcome() == Outcome.UPDATED || d.outcome() == Outcome.INSTALLED);
        return changed ? "SUCCESS" : "UP_TO_DATE";
    }

    public DiscordNotifier.Level level() {
        return switch (overallState()) {
            case "FAILED" -> DiscordNotifier.Level.ERROR;
            case "PARTIAL" -> DiscordNotifier.Level.WARNING;
            case "SUCCESS" -> DiscordNotifier.Level.SUCCESS;
            default -> DiscordNotifier.Level.INFO;
        };
    }

    /** Resume sur une ligne, ex. {@code OSM : mis a jour (version du 10/09/2026) - BAN : deja a jour (version du 05/09/2026)}. */
    public String summaryLine() {
        List<String> parts = new ArrayList<>();
        for (DatasetResult d : datasets) {
            StringBuilder sb = new StringBuilder(d.shortLabel()).append(" : ").append(d.outcome().label());
            if (d.outcome() == Outcome.FAILED) {
                if (d.message() != null) {
                    sb.append(" (").append(d.message()).append(')');
                }
            } else {
                Instant version = d.after() != null ? d.after().version() : null;
                if (version != null) {
                    sb.append(" (version du ").append(DATE.format(version)).append(')');
                }
            }
            parts.add(sb.toString());
        }
        return String.join(" - ", parts);
    }

    /** Corps de l'embed Discord (markdown). Egalement logge tel quel. */
    public String toDiscordMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("**Declencheur** : ").append(trigger.label()).append('\n');
        sb.append("**Machine** : ").append(machineLine()).append('\n');
        sb.append("**Debut** : ").append(DATE_TIME.format(startedAt)).append(" (Europe/Paris)");
        if (finishedAt != null) {
            sb.append(" - **duree totale** : ").append(formatDuration(Duration.between(startedAt, finishedAt)));
        }
        sb.append('\n');

        for (DatasetResult d : datasets) {
            Snapshot before = d.before() != null ? d.before() : Snapshot.empty();
            Snapshot after = d.after() != null ? d.after() : Snapshot.empty();
            sb.append('\n').append(d.emoji()).append(" **").append(d.label()).append("** - ")
                    .append(d.outcome().emoji()).append(' ').append(d.outcome().label()).append('\n');
            switch (d.outcome()) {
                case UPDATED, INSTALLED -> {
                    bullet(sb, "Version publiee", transition(formatDateTime(before.version()), formatDateTime(after.version())));
                    if (d.remoteFileName() != null) {
                        bullet(sb, "Fichier distant", d.remoteFileName());
                    }
                    bullet(sb, "Fichier", transitionBytes(before.fileBytes(), after.fileBytes()));
                    for (Map.Entry<String, Long> metric : after.metrics().entrySet()) {
                        bullet(sb, metric.getKey(), transitionCount(before.metrics().get(metric.getKey()), metric.getValue()));
                    }
                    String durations = durations(d);
                    if (!durations.isEmpty()) {
                        bullet(sb, "Durees", durations);
                    }
                }
                case UP_TO_DATE -> {
                    bullet(sb, "Version publiee", formatDateTime(after.version()));
                    bullet(sb, "Fichier", formatBytes(after.fileBytes()));
                    for (Map.Entry<String, Long> metric : after.metrics().entrySet()) {
                        bullet(sb, metric.getKey(), formatCount(metric.getValue()));
                    }
                }
                case FAILED -> {
                    bullet(sb, "Erreur", d.message() != null ? d.message() : "voir les logs du serveur");
                    if (before.version() != null) {
                        bullet(sb, "Version locale conservee", formatDateTime(before.version()));
                    }
                    String durations = durations(d);
                    if (!durations.isEmpty()) {
                        bullet(sb, "Durees", durations);
                    }
                }
            }
            if (d.outcome() != Outcome.FAILED && d.message() != null) {
                bullet(sb, "Note", d.message());
            }
            bullet(sb, "Source", d.url());
        }
        return sb.toString();
    }

    private static String durations(DatasetResult d) {
        List<String> parts = new ArrayList<>();
        if (d.downloadDuration() != null) {
            parts.add("telechargement " + formatDuration(d.downloadDuration()));
        }
        if (d.rebuildDuration() != null) {
            parts.add("reconstruction " + formatDuration(d.rebuildDuration()));
        }
        return String.join(" - ", parts);
    }

    private static void bullet(StringBuilder sb, String key, String value) {
        sb.append("• ").append(key).append(" : ").append(value).append('\n');
    }

    static String transition(String from, String to) {
        return from.equals(to) ? to : from + ARROW + to;
    }

    static String transitionBytes(long before, long after) {
        if (before < 0) {
            return formatBytes(after);
        }
        long delta = after - before;
        String deltaText = delta == 0 ? "inchange" : (delta > 0 ? "+" : "-") + formatBytes(Math.abs(delta));
        return formatBytes(before) + ARROW + formatBytes(after) + " (" + deltaText + ")";
    }

    static String transitionCount(Long before, long after) {
        if (before == null) {
            return formatCount(after);
        }
        long delta = after - before;
        String deltaText = delta == 0 ? "inchange" : (delta > 0 ? "+" : "-") + formatCount(Math.abs(delta));
        return formatCount(before) + ARROW + formatCount(after) + " (" + deltaText + ")";
    }

    /** Entier avec separateur de milliers (espace simple, lisible partout : {@code 1 234 567}). */
    public static String formatCount(long n) {
        return String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ');
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " o";
        }
        String[] units = {"Ko", "Mo", "Go", "To"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.FRANCE, value < 10 ? "%.2f %s" : "%.1f %s", value, units[unit]);
    }

    public static String formatDuration(Duration duration) {
        if (duration == null) {
            return "?";
        }
        long seconds = Math.max(0, duration.getSeconds());
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return h + " h " + String.format("%02d", m) + " min" + (s > 0 ? " " + String.format("%02d", s) + " s" : "");
        }
        if (m > 0) {
            return m + " min" + (s > 0 ? " " + s + " s" : "");
        }
        return s + " s";
    }

    public static String formatDateTime(Instant instant) {
        return instant == null ? "inconnue" : DATE_TIME.format(instant);
    }
}
