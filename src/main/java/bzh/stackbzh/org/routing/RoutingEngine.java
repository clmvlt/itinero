package bzh.stackbzh.org.routing;

import bzh.stackbzh.org.status.ComponentState;
import bzh.stackbzh.org.status.StatusRegistry;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.PointList;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

@Component
public class RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngine.class);
    public static final String COMPONENT = "Routing";

    private final String osmFile;
    private final String graphCache;
    private final String profile;
    private final boolean useCh;
    private final StatusRegistry status;

    private final Object lock = new Object();
    private volatile GraphHopper hopper;

    public RoutingEngine(
            @Value("${app.routing.osm-file}") String osmFile,
            @Value("${app.routing.graph-cache}") String graphCache,
            @Value("${app.routing.profile}") String profile,
            @Value("${app.routing.use-ch}") boolean useCh,
            StatusRegistry status) {
        this.osmFile = osmFile;
        this.graphCache = graphCache;
        this.profile = profile;
        this.useCh = useCh;
        this.status = status;
    }

    public void initialize() {
        synchronized (lock) {
            this.hopper = buildOrNull(false);
        }
    }

    public void reload(boolean reimport) {
        synchronized (lock) {
            GraphHopper old = this.hopper;
            this.hopper = null;
            if (old != null) {
                old.close();
            }
            if (reimport) {
                deleteDirectory(Path.of(graphCache));
            }
            this.hopper = buildOrNull(true);
        }
    }

    private GraphHopper buildOrNull(boolean isReload) {
        Path osm = Path.of(osmFile);
        Path cache = Path.of(graphCache);
        boolean hasCache = Files.isDirectory(cache)
                && cache.toFile().list() != null
                && cache.toFile().list().length > 0;

        if (!Files.exists(osm) && !hasCache) {
            String msg = "Fichier OSM absent (" + osm.toAbsolutePath() + "). Voir data/README.md.";
            log.warn("Routing DESACTIVE : {}", msg);
            status.setComponent(COMPONENT, ComponentState.DISABLED, msg);
            return null;
        }
        try {
            String phase = hasCache && !isReload
                    ? "Chargement du graphe routier en RAM..."
                    : "Construction du graphe routier (peut prendre plusieurs minutes)...";
            log.info("GraphHopper : {}", phase);
            status.setComponent(COMPONENT, ComponentState.INITIALIZING, phase);

            GraphHopper gh = new GraphHopper();
            gh.setOSMFile(osmFile);
            gh.setGraphHopperLocation(graphCache);
            gh.setEncodedValuesString("car_access, car_average_speed");
            CustomModel customModel = GHUtility.loadCustomModelFromJar(profile + ".json");
            gh.setProfiles(new Profile(profile).setCustomModel(customModel));
            if (useCh) {
                gh.getCHPreparationHandler().setCHProfiles(new CHProfile(profile));
            }
            gh.importOrLoad();

            status.setComponent(COMPONENT, ComponentState.READY, "Graphe routier pret (profil " + profile + ").");
            log.info("GraphHopper pret.");
            return gh;
        } catch (Exception e) {
            log.error("Echec de l'initialisation de GraphHopper : routing desactive.", e);
            status.setComponent(COMPONENT, ComponentState.ERROR, "Echec : " + e.getMessage());
            return null;
        }
    }

    public boolean isReady() {
        return hopper != null;
    }

    public String profileName() {
        return profile;
    }

    public Leg route(double fromLat, double fromLon, double toLat, double toLon) {
        GraphHopper gh = ensureReady();
        GHRequest req = new GHRequest(fromLat, fromLon, toLat, toLon).setProfile(profile);
        GHResponse rsp = gh.route(req);
        if (rsp.hasErrors()) {
            throw new RoutingException("Calcul d'itineraire impossible : " + rsp.getErrors());
        }
        ResponsePath path = rsp.getBest();
        return new Leg(path.getDistance(), path.getTime() / 1000L, toGeometry(path.getPoints()));
    }

    public long travelTimeSeconds(double fromLat, double fromLon, double toLat, double toLon) {
        GraphHopper gh = ensureReady();
        GHRequest req = new GHRequest(fromLat, fromLon, toLat, toLon).setProfile(profile);
        GHResponse rsp = gh.route(req);
        if (rsp.hasErrors()) {
            throw new RoutingException("Calcul de matrice impossible : " + rsp.getErrors());
        }
        return rsp.getBest().getTime() / 1000L;
    }

    private GraphHopper ensureReady() {
        GraphHopper gh = hopper;
        if (gh == null) {
            throw new RoutingException("Moteur de routing indisponible : "
                    + "fichier OSM manquant ou graphe non construit (voir data/README.md).");
        }
        return gh;
    }

    private static double[][] toGeometry(PointList points) {
        double[][] geometry = new double[points.size()][2];
        for (int i = 0; i < points.size(); i++) {
            geometry[i][0] = points.getLat(i);
            geometry[i][1] = points.getLon(i);
        }
        return geometry;
    }

    private static void deleteDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Impossible de supprimer {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("Impossible de nettoyer le cache du graphe {}", dir, e);
        }
    }

    @PreDestroy
    void close() {
        if (hopper != null) {
            hopper.close();
        }
    }

    public record Leg(double distanceMeters, long durationSeconds, double[][] geometry) {
    }

    public static class RoutingException extends RuntimeException {
        public RoutingException(String message) {
            super(message);
        }
    }
}
