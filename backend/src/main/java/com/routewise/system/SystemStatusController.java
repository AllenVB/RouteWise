package com.routewise.system;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Phase 1a scaffolding: proves the service chain is wired up.
 *
 * <p>The frontend talks only to the backend; the backend is the single owner of
 * data and the only caller of the Python service. This endpoint exists so that
 * service-to-service communication is exercised from day one rather than being
 * discovered broken in a later phase.
 */
@RestController
@RequestMapping("/system")
public class SystemStatusController {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusController.class);

    private final RestClient optimizerClient;

    public SystemStatusController(RestClient optimizerClient) {
        this.optimizerClient = optimizerClient;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "backend", Map.of("status", "ok"),
                "optimizer", optimizerStatus());
    }

    private Map<String, Object> optimizerStatus() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = optimizerClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of("status", "empty") : body;
        } catch (Exception e) {
            // Degrade rather than fail: the backend is still usable for data
            // operations when the optimizer is down.
            log.warn("optimizer unreachable: {}", e.getMessage());
            return Map.of("status", "unreachable", "error", String.valueOf(e.getMessage()));
        }
    }
}
