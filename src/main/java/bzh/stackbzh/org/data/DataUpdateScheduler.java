package bzh.stackbzh.org.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Point d'entree planifie de la mise a jour des donnees (defaut : dimanche 8h, Europe/Paris,
 * cron {@code app.data.update-cron}). Toute la logique (verification de fraicheur, telechargement,
 * reconstruction, compte rendu Discord) est dans {@link DataUpdateService}, partagee avec la
 * verification au demarrage.
 */
@Component
public class DataUpdateScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataUpdateScheduler.class);

    private final DataUpdateService updateService;

    public DataUpdateScheduler(DataUpdateService updateService) {
        this.updateService = updateService;
    }

    @Scheduled(cron = "${app.data.update-cron}", zone = "Europe/Paris")
    public void updateData() {
        log.info("Declenchement planifie de la mise a jour des donnees.");
        updateService.run(UpdateTrigger.SCHEDULED);
    }
}
