package bzh.stackbzh.org.status;

import bzh.stackbzh.org.data.DataBootstrap;
import bzh.stackbzh.org.data.DataDownloadService.DownloadResult;
import bzh.stackbzh.org.data.DataUpdateService;
import bzh.stackbzh.org.geocoding.AddressSearchService;
import bzh.stackbzh.org.routing.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Chargement asynchrone des donnees apres le demarrage de Tomcat, dans l'ordre (RAM maitrisee) :
 * telechargement initial OSM si absent -> graphe routier -> telechargement initial BAN si absent
 * -> index d'adresses -> puis {@link DataUpdateService#runOnStartup} (verification qu'une version
 * plus recente n'est pas publiee, mise a jour + compte rendu Discord le cas echeant). L'API est
 * donc servie avec les donnees existantes pendant qu'une eventuelle mise a jour se telecharge.
 */
@Component
public class StartupOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StartupOrchestrator.class);

    private final DataBootstrap dataBootstrap;
    private final RoutingEngine routingEngine;
    private final AddressSearchService addressSearchService;
    private final DataUpdateService dataUpdateService;
    private final StatusRegistry status;

    public StartupOrchestrator(DataBootstrap dataBootstrap, RoutingEngine routingEngine,
                               AddressSearchService addressSearchService, DataUpdateService dataUpdateService,
                               StatusRegistry status) {
        this.dataBootstrap = dataBootstrap;
        this.routingEngine = routingEngine;
        this.addressSearchService = addressSearchService;
        this.dataUpdateService = dataUpdateService;
        this.status = status;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        status.setComponent(RoutingEngine.COMPONENT, ComponentState.WAITING, "En attente de chargement...");
        status.setComponent(AddressSearchService.COMPONENT, ComponentState.WAITING, "En attente de chargement...");

        Thread thread = new Thread(this::loadAll, "startup-init");
        thread.setDaemon(true);
        thread.start();
    }

    private void loadAll() {
        log.info("Chargement asynchrone des donnees : debut.");
        DownloadResult osmInitial = null;
        DownloadResult banInitial = null;
        try {
            osmInitial = dataBootstrap.ensureOsm();
            routingEngine.initialize();
        } catch (Exception e) {
            log.error("Initialisation du routing en echec.", e);
            status.setComponent(RoutingEngine.COMPONENT, ComponentState.ERROR, "Echec : " + e.getMessage());
        }
        try {
            banInitial = dataBootstrap.ensureBan();
            addressSearchService.initialize();
        } catch (Exception e) {
            log.error("Initialisation du geocoding en echec.", e);
            status.setComponent(AddressSearchService.COMPONENT, ComponentState.ERROR, "Echec : " + e.getMessage());
        }
        log.info("Chargement asynchrone des donnees : termine.");

        try {
            dataUpdateService.runOnStartup(osmInitial, banInitial);
        } catch (Exception e) {
            log.error("Verification des mises a jour au demarrage en echec.", e);
        }
    }
}
