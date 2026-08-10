package bzh.stackbzh.org.common;

import bzh.stackbzh.org.geocoding.AddressSearchService;
import bzh.stackbzh.org.notification.DiscordNotifier;
import bzh.stackbzh.org.routing.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final DiscordNotifier notifier;

    public ApiExceptionHandler(DiscordNotifier notifier) {
        this.notifier = notifier;
    }

    @ExceptionHandler({
            RoutingEngine.RoutingException.class,
            AddressSearchService.GeocodingUnavailableException.class,
            ServiceUnavailableException.class})
    public ProblemDetail handleUnavailable(RuntimeException ex) {
        notifier.notifyError("Service indisponible (503)", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        pd.setTitle("Service indisponible");
        return pd;
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ProblemDetail handleBadRequest(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Requete invalide");
        return pd;
    }

    /**
     * Filet de securite : toute exception non prevue devient un 500 ProblemDetail
     * ET est signalee sur Discord. Les erreurs web standard de Spring (404 ressource
     * inconnue, 400 de validation @Valid, 405...) implementent {@link ErrorResponse} :
     * on renvoie leur ProblemDetail d'origine sans notifier (erreurs client, pas de spam).
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            return er.getBody();
        }
        log.error("Erreur interne non geree", ex);
        notifier.notifyError("Erreur interne (500)", ex.toString());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        pd.setTitle("Erreur interne");
        return pd;
    }

    public static class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException(String message) {
            super(message);
        }
    }
}
