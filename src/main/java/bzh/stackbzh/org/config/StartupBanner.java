package bzh.stackbzh.org.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupBanner {

    private static final Logger log = LoggerFactory.getLogger(StartupBanner.class);

    private final Environment env;

    public StartupBanner(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String port = firstNonBlank(env.getProperty("local.server.port"),
                env.getProperty("server.port"), "8080");
        String contextPath = trimToEmpty(env.getProperty("server.servlet.context-path"));
        String base = "http://localhost:" + port + contextPath;

        String swaggerPath = orDefault(env.getProperty("springdoc.swagger-ui.path"), "/swagger-ui.html");
        String apiDocsPath = orDefault(env.getProperty("springdoc.api-docs.path"), "/v3/api-docs");

        String line = "=".repeat(72);
        String sep = "-".repeat(72);
        String banner = '\n' + line + '\n'
                + "  spring-org - SERVEUR PRET" + '\n'
                + sep + '\n'
                + "  Tableau de bord : " + base + "/            (etat en direct)" + '\n'
                + "  API             : " + base + '\n'
                + "  Swagger UI      : " + base + swaggerPath + '\n'
                + "  OpenAPI (JSON)  : " + base + apiDocsPath + '\n'
                + sep + '\n'
                + "  Chargement des donnees en arriere-plan : suivez la progression" + '\n'
                + "  sur le tableau de bord ci-dessus." + '\n'
                + line;
        log.info(banner);
    }

    private static String orDefault(String value, String def) {
        return value == null || value.isBlank() ? def : value;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
