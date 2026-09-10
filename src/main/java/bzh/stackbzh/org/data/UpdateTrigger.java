package bzh.stackbzh.org.data;

/**
 * Origine d'une mise a jour des donnees : QUI l'a declenchee. Figure dans le compte rendu
 * Discord, les logs et {@code GET /status} (champ {@code dataUpdate.last.trigger}).
 */
public enum UpdateTrigger {
    /** Verification lancee automatiquement au demarrage de l'API, une fois les moteurs charges. */
    STARTUP("Demarrage de l'API"),
    /** Execution planifiee par le cron {@code app.data.update-cron} (dimanche 8h par defaut). */
    SCHEDULED("Planificateur hebdomadaire");

    private final String label;

    UpdateTrigger(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
