package com.routewise.optimization.client;

import java.net.SocketTimeoutException;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Turns optimizer failures into the API's error catalogue, in one place.
 *
 * <pre>
 * 409 from the optimizer   -> NO_FEASIBLE_SOLUTION, its message passed through
 * other 4xx                -> INTERNAL_ERROR: we sent a malformed request, our bug
 * read timeout             -> OPTIMIZER_TIMEOUT (504)
 * refused / unreachable    -> OPTIMIZER_UNAVAILABLE (502)
 * </pre>
 *
 * Telling timeout from refusal matters to the user: one means "try a smaller
 * plan or wait", the other "the service is down".
 */
public final class OptimizerCalls {

    private static final Logger log = LoggerFactory.getLogger(OptimizerCalls.class);

    private OptimizerCalls() {}

    public static <T> T call(String what, Supplier<T> request) {
        T body;
        try {
            body = request.get();
        } catch (HttpClientErrorException.Conflict e) {
            throw new ApiException(ErrorCode.NO_FEASIBLE_SOLUTION, conflictMessage(e), e);
        } catch (HttpClientErrorException e) {
            log.error("{}: optimizer istegi reddetti ({}): {}",
                    what, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "Rota servisi isteği reddetti. Bu bir sistem hatasıdır.", e);
        } catch (ResourceAccessException e) {
            // Bağlantı kurma zaman aşımı da SocketTimeoutException'dır ama servis
            // kapalı demektir; yalnızca okuma zaman aşımı "hesaplama uzun sürdü"dür.
            if (e.getCause() instanceof SocketTimeoutException timeout
                    && timeout.getMessage() != null && timeout.getMessage().contains("Read timed out")) {
                log.warn("{}: optimizer zaman asimi", what);
                throw new ApiException(ErrorCode.OPTIMIZER_TIMEOUT,
                        "Rota hesaplaması zaman aşımına uğradı. Biraz sonra tekrar dene.", e);
            }
            log.warn("{}: optimizer erisilemedi: {}", what, e.getMessage());
            throw new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE,
                    "Rota hesaplama servisine ulaşılamadı.", e);
        } catch (RestClientException e) {
            log.warn("{}: optimizer hatasi: {}", what, e.getMessage());
            throw new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE,
                    "Rota hesaplama servisi şu an yanıt veremiyor.", e);
        }
        if (body == null) {
            throw new ApiException(ErrorCode.OPTIMIZER_UNAVAILABLE, "Rota servisi boş yanıt döndürdü.");
        }
        return body;
    }

    /** The optimizer answers 409 with {@code {"detail": {"code", "message"}}}. */
    private static String conflictMessage(HttpClientErrorException e) {
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            String message = body == null ? null : body.path("detail").path("message").asText(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException parse) {
            log.warn("optimizer 409 govdesi okunamadi: {}", e.getResponseBodyAsString());
        }
        return "Bu kargolar mevcut araç ve kısıtlarla planlanamıyor.";
    }
}
