package bzh.stackbzh.org.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Envoie les erreurs metier de l'API vers un webhook Discord (embed rouge).
 *
 * - Desactive si {@code app.notifications.discord-webhook-url} est vide.
 * - Envoi ASYNCHRONE (thread daemon dedie) : ne ralentit jamais une requete.
 * - Ne leve JAMAIS d'exception : un echec d'envoi est seulement logge en WARN
 *   (sinon une panne Discord provoquerait des erreurs... qu'on essaierait de notifier).
 * - Anti-spam : un message identique (titre + details) n'est pas renvoye
 *   pendant {@link #COOLDOWN_MS} (utile quand le routing est KO et que chaque
 *   requete declenche le meme 503).
 */
@Service
public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

    /** Limite Discord : 4096 chars par description d'embed. On garde de la marge. */
    private static final int MAX_DESCRIPTION = 3500;
    private static final long COOLDOWN_MS = 60_000;
    private static final int COLOR_RED = 0xE74C3C;

    private final String webhookUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "discord-notifier");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    public DiscordNotifier(@Value("${app.notifications.discord-webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        if (this.webhookUrl.isEmpty()) {
            log.warn("Notifications Discord DESACTIVEES (app.notifications.discord-webhook-url vide)");
        } else {
            log.info("Notifications Discord activees");
        }
    }

    /**
     * Signale une erreur sur Discord. Non bloquant, jamais d'exception.
     *
     * @param title   titre court de l'embed (ex : "Routing indisponible (503)")
     * @param details description detaillee (tronquee a {@value #MAX_DESCRIPTION} chars)
     */
    public void notifyError(String title, String details) {
        if (webhookUrl.isEmpty()) {
            return;
        }
        String key = title + "|" + details;
        long now = System.currentTimeMillis();
        Long previous = lastSent.get(key);
        if (previous != null && now - previous < COOLDOWN_MS) {
            return;
        }
        if (lastSent.size() > 1000) {
            lastSent.clear();
        }
        lastSent.put(key, now);
        executor.submit(() -> send(title, details));
    }

    private void send(String title, String details) {
        try {
            String description = details == null ? "" : details;
            if (description.length() > MAX_DESCRIPTION) {
                description = description.substring(0, MAX_DESCRIPTION) + "\n[... tronque]";
            }
            Map<String, Object> embed = Map.of(
                    "title", title,
                    "description", description,
                    "color", COLOR_RED,
                    "timestamp", Instant.now().toString());
            String body = mapper.writeValueAsString(Map.of("embeds", List.of(embed)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Webhook Discord : HTTP {} — {}", response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Echec d'envoi de la notification Discord : {}", e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
