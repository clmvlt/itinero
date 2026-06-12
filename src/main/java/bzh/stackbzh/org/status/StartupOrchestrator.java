package bzh.stackbzh.org.status;

import bzh.stackbzh.org.data.DataBootstrap;
import bzh.stackbzh.org.geocoding.AddressSearchService;
import bzh.stackbzh.org.routing.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StartupOrchestrator.class);

    private final DataBootstrap dataBootstrap;
    private final RoutingEngine routingEngine;
    private final AddressSearchService addressSearchService;
    private final StatusRegistry status;

    public StartupOrchestrator(DataBootstrap dataBootstrap, RoutingEngine routingEngine,
                               AddressSearchService addressSearchService, StatusRegistry status) {
        this.dataBootstrap = dataBootstrap;
        this.routingEngine = routingEngine;
        this.addressSearchService = addressSearchService;
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
        try {
            dataBootstrap.ensureOsm();
            routingEngine.initialize();
        } catch (Exception e) {
            log.error("Initialisation du routing en echec.", e);
            status.setComponent(RoutingEngine.COMPONENT, ComponentState.ERROR, "Echec : " + e.getMessage());
        }
        try {
            dataBootstrap.ensureBan();
            addressSearchService.initialize();
        } catch (Exception e) {
            log.error("Initialisation du geocoding en echec.", e);
            status.setComponent(AddressSearchService.COMPONENT, ComponentState.ERROR, "Echec : " + e.getMessage());
        }
        log.info("Chargement asynchrone des donnees : termine.");
    }
}
