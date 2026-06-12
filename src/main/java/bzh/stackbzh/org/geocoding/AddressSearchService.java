package bzh.stackbzh.org.geocoding;

import bzh.stackbzh.org.geocoding.dto.AddressResult;
import bzh.stackbzh.org.status.ComponentState;
import bzh.stackbzh.org.status.StatusRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Service
public class AddressSearchService {

    private static final Logger log = LoggerFactory.getLogger(AddressSearchService.class);
    private static final String SEARCH_FIELD = "search";
    public static final String COMPONENT = "Geocoding";

    private static final Pattern HOUSE_NUMBER = Pattern.compile("^\\d{1,4}[a-z]?$");
    private static final int MAX_POOL = 6000;

    private final String banFile;
    private final String indexDir;
    private final boolean rebuildOnStart;
    private final double proximityScaleMeters;
    private final StatusRegistry status;

    private final Object lock = new Object();
    private Directory directory;
    private DirectoryReader reader;
    private volatile IndexSearcher searcher;

    public AddressSearchService(
            @Value("${app.geocoding.ban-file}") String banFile,
            @Value("${app.geocoding.index-dir}") String indexDir,
            @Value("${app.geocoding.rebuild-on-start}") boolean rebuildOnStart,
            @Value("${app.geocoding.proximity-scale-meters}") double proximityScaleMeters,
            StatusRegistry status) {
        this.banFile = banFile;
        this.indexDir = indexDir;
        this.rebuildOnStart = rebuildOnStart;
        this.proximityScaleMeters = proximityScaleMeters > 0 ? proximityScaleMeters : 10000.0;
        this.status = status;
    }

    public void initialize() {
        try {
            Path indexPath = Path.of(indexDir);
            Path csvPath = Path.of(banFile);
            boolean indexExists = indexExists(indexPath);

            if (rebuildOnStart || !indexExists) {
                if (!Files.exists(csvPath)) {
                    if (indexExists) {
                        log.info("CSV BAN absent mais index existant : on garde l'index en place.");
                    } else {
                        String msg = "CSV BAN absent (" + csvPath.toAbsolutePath() + "). Voir data/README.md.";
                        log.warn("Geocoding DESACTIVE : {}", msg);
                        status.setComponent(COMPONENT, ComponentState.DISABLED, msg);
                        return;
                    }
                } else {
                    buildIndex(indexPath, csvPath);
                }
            } else {
                status.setComponent(COMPONENT, ComponentState.INITIALIZING, "Chargement de l'index d'adresses...");
            }

            openSearcher(indexPath);
            status.setComponent(COMPONENT, ComponentState.READY,
                    "Index d'adresses pret (" + documentCount() + " adresses).");
        } catch (Exception e) {
            log.error("Echec de l'initialisation de la recherche d'adresse : geocoding desactive.", e);
            status.setComponent(COMPONENT, ComponentState.ERROR, "Echec : " + e.getMessage());
        }
    }

    public int documentCount() {
        DirectoryReader r = this.reader;
        return r != null ? r.numDocs() : -1;
    }

    public void reload() {
        Path indexPath = Path.of(indexDir);
        Path csvPath = Path.of(banFile);
        if (!Files.exists(csvPath)) {
            log.warn("Reconstruction de l'index ignoree : CSV BAN introuvable ({}).", csvPath.toAbsolutePath());
            return;
        }
        try {
            buildIndex(indexPath, csvPath);
            openSearcher(indexPath);
            status.setComponent(COMPONENT, ComponentState.READY,
                    "Index d'adresses pret (" + documentCount() + " adresses).");
        } catch (Exception e) {
            log.error("Echec de la reconstruction de l'index d'adresses.", e);
            status.setComponent(COMPONENT, ComponentState.ERROR, "Echec : " + e.getMessage());
        }
    }

    private static boolean indexExists(Path indexPath) throws IOException {
        if (!Files.isDirectory(indexPath)) {
            return false;
        }
        try (Directory dir = FSDirectory.open(indexPath)) {
            return DirectoryReader.indexExists(dir);
        }
    }

    private void openSearcher(Path indexPath) throws Exception {
        synchronized (lock) {
            DirectoryReader oldReader = this.reader;
            Directory oldDirectory = this.directory;

            Directory dir = FSDirectory.open(indexPath);
            DirectoryReader newReader = DirectoryReader.open(dir);
            this.directory = dir;
            this.reader = newReader;
            this.searcher = new IndexSearcher(newReader);
            log.info("Index d'adresses pret ({} documents).", newReader.numDocs());

            if (oldReader != null) {
                try {
                    oldReader.close();
                } catch (Exception e) {
                    log.warn("Fermeture de l'ancien reader impossible", e);
                }
            }
            if (oldDirectory != null) {
                try {
                    oldDirectory.close();
                } catch (Exception e) {
                    log.warn("Fermeture de l'ancien directory impossible", e);
                }
            }
        }
    }

    private void buildIndex(Path indexPath, Path csvPath) throws Exception {
        log.info("Construction de l'index d'adresses a partir de {} (peut prendre plusieurs minutes)...", csvPath);
        status.setComponent(COMPONENT, ComponentState.INITIALIZING, "Construction de l'index d'adresses...");
        Files.createDirectories(indexPath);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(';')
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (Directory dir = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer())
                     .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                     .setRAMBufferSizeMB(256));
             Reader in = new InputStreamReader(
                     new GZIPInputStream(Files.newInputStream(csvPath)), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(in, format)) {

            long count = 0;
            for (CSVRecord record : parser) {
                String num = col(record, "numero");
                String street = col(record, "nom_voie");
                String postcode = col(record, "code_postal");
                String city = col(record, "nom_commune");
                String latStr = col(record, "lat");
                String lonStr = col(record, "lon");
                if (street.isEmpty() || latStr.isEmpty() || lonStr.isEmpty()) {
                    continue;
                }
                double lat;
                double lon;
                try {
                    lat = Double.parseDouble(latStr);
                    lon = Double.parseDouble(lonStr);
                } catch (NumberFormatException e) {
                    continue;
                }
                String label = buildLabel(num, street, postcode, city);
                Document doc = new Document();
                doc.add(new TextField(SEARCH_FIELD, normalize(label), Field.Store.NO));
                doc.add(new StoredField("label", label));
                doc.add(new StoredField("num", num));
                doc.add(new StoredField("street", street));
                doc.add(new StoredField("postcode", postcode));
                doc.add(new StoredField("city", city));
                doc.add(new StoredField("lat", lat));
                doc.add(new StoredField("lon", lon));
                writer.addDocument(doc);

                if (++count % 1_000_000 == 0) {
                    log.info("  {} adresses indexees...", count);
                    status.setComponent(COMPONENT, ComponentState.INITIALIZING,
                            "Indexation des adresses : " + count + "...");
                }
            }
            writer.commit();
            log.info("Index construit : {} adresses.", count);
        }
    }

    public boolean isReady() {
        return searcher != null;
    }

    public List<AddressResult> search(String query, int limit) {
        return search(query, limit, null, null);
    }

    public List<AddressResult> search(String query, int limit, Double lat, Double lon) {
        IndexSearcher current = searcher;
        if (current == null) {
            throw new GeocodingUnavailableException(
                    "Recherche d'adresse indisponible : CSV BAN manquant ou index non construit (voir data/README.md).");
        }
        String[] tokens = tokenize(query);
        Query q = buildQuery(tokens);
        if (q == null) {
            return List.of();
        }
        boolean hasPosition = lat != null && lon != null;
        boolean streetLevel = !hasHouseNumber(tokens);
        int poolSize = poolSize(limit, streetLevel, hasPosition);
        try {
            List<Candidate> candidates = collect(current, q, poolSize, hasPosition, lat, lon);
            String normQuery = joinTokens(tokens);
            for (Candidate c : candidates) {
                double rel = c.textScore * streetBoost(normalize(c.street), normQuery);
                c.finalScore = (float) (hasPosition ? rel * decay(c.distanceMeters) : rel);
            }
            candidates.sort((a, b) -> {
                int byScore = Float.compare(b.finalScore, a.finalScore);
                if (byScore != 0) {
                    return byScore;
                }
                return Double.compare(a.distanceMeters, b.distanceMeters);
            });

            List<AddressResult> results = new ArrayList<>();
            Set<String> seenStreets = streetLevel ? new HashSet<>() : null;
            for (Candidate c : candidates) {
                if (streetLevel) {
                    String key = normalize(c.street) + '|' + c.postcode + '|' + c.city;
                    if (!seenStreets.add(key)) {
                        continue;
                    }
                }
                results.add(c.toResult(streetLevel, hasPosition));
                if (results.size() >= limit) {
                    break;
                }
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Erreur lors de la recherche d'adresse", e);
        }
    }

    private List<Candidate> collect(IndexSearcher current, Query q, int poolSize,
                                    boolean hasPosition, Double lat, Double lon) throws IOException {
        TopDocs top = current.search(q, poolSize);
        List<Candidate> candidates = new ArrayList<>();
        var storedFields = current.storedFields();
        for (ScoreDoc sd : top.scoreDocs) {
            Candidate c = toCandidate(storedFields.document(sd.doc), sd.score);
            c.distanceMeters = hasPosition ? haversineMeters(lat, lon, c.lat, c.lon) : Double.NaN;
            candidates.add(c);
        }
        return candidates;
    }

    private static Candidate toCandidate(Document doc, float textScore) {
        Candidate c = new Candidate();
        c.label = doc.get("label");
        c.num = doc.get("num");
        c.street = doc.get("street");
        c.postcode = doc.get("postcode");
        c.city = doc.get("city");
        c.lat = doc.getField("lat").numericValue().doubleValue();
        c.lon = doc.getField("lon").numericValue().doubleValue();
        c.textScore = textScore;
        return c;
    }

    private double decay(double distanceMeters) {
        if (Double.isNaN(distanceMeters) || distanceMeters < 0) {
            return 1.0;
        }
        return proximityScaleMeters / (proximityScaleMeters + distanceMeters);
    }

    private static double streetBoost(String normStreet, String normQuery) {
        if (normQuery.isEmpty()) {
            return 1.0;
        }
        if (normStreet.equals(normQuery)) {
            return 8.0;
        }
        if (normStreet.startsWith(normQuery)) {
            return 2.0;
        }
        return 1.0;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static int poolSize(int limit, boolean streetLevel, boolean hasPosition) {
        if (hasPosition) {
            return Math.min(Math.max(limit * 100, 3000), MAX_POOL);
        }
        if (streetLevel) {
            return Math.min(Math.max(limit * 60, 600), MAX_POOL);
        }
        return limit;
    }

    private static String joinTokens(String[] tokens) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (t.isEmpty() || hasDigit(t)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String[] tokenize(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new String[0];
        }
        return normalize(rawQuery.trim()).split("[^\\p{Alnum}]+");
    }

    private static boolean hasHouseNumber(String[] tokens) {
        for (String token : tokens) {
            if (HOUSE_NUMBER.matcher(token).matches()) {
                return true;
            }
        }
        return false;
    }

    private Query buildQuery(String[] tokens) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int added = 0;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                continue;
            }
            Term term = new Term(SEARCH_FIELD, token);
            boolean isLast = (i == tokens.length - 1);
            Query clause = isLast ? new PrefixQuery(term) : new TermQuery(term);
            builder.add(clause, BooleanClause.Occur.MUST);
            added++;
        }
        return added == 0 ? null : builder.build();
    }

    private static final class Candidate {
        String label;
        String num;
        String street;
        String postcode;
        String city;
        double lat;
        double lon;
        float textScore;
        double distanceMeters;
        float finalScore;

        AddressResult toResult(boolean streetLevel, boolean hasPosition) {
            Double dist = hasPosition && !Double.isNaN(distanceMeters) && !Double.isInfinite(distanceMeters)
                    ? Math.round(distanceMeters * 10.0) / 10.0 : null;
            if (streetLevel) {
                return new AddressResult(buildLabel("", street, postcode, city), "",
                        street, postcode, city, lat, lon, "street", dist, finalScore);
            }
            return new AddressResult(label, num, street, postcode, city, lat, lon,
                    "housenumber", dist, finalScore);
        }
    }

    private static String buildLabel(String num, String street, String postcode, String city) {
        StringBuilder sb = new StringBuilder();
        if (!num.isEmpty()) {
            sb.append(num).append(' ');
        }
        sb.append(street);
        if (!postcode.isEmpty() || !city.isEmpty()) {
            sb.append(", ");
            if (!postcode.isEmpty()) {
                sb.append(postcode).append(' ');
            }
            sb.append(city);
        }
        return sb.toString().trim();
    }

    private static String col(CSVRecord record, String name) {
        if (!record.isMapped(name) || !record.isSet(name)) {
            return "";
        }
        String v = record.get(name);
        return v == null ? "" : v.trim();
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT);
    }

    @PreDestroy
    void close() throws Exception {
        if (reader != null) {
            reader.close();
        }
        if (directory != null) {
            directory.close();
        }
    }

    public static class GeocodingUnavailableException extends RuntimeException {
        public GeocodingUnavailableException(String message) {
            super(message);
        }
    }
}
