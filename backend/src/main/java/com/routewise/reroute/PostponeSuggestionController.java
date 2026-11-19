package com.routewise.reroute;

import java.util.List;

import com.routewise.auth.AuthenticatedUser;
import com.routewise.reroute.PostponeSuggestionService.Suggestion;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reroute/suggestions")
@Tag(name = "Erteleme önerileri", description = "Maliyet artışı eşiğini aşan adres değişiklikleri (firma)")
public class PostponeSuggestionController {

    private final PostponeSuggestionService service;

    public PostponeSuggestionController(PostponeSuggestionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Açık erteleme önerileri",
            description = "Bugün onaylanan ve kalan turu eşikten (varsayılan %15) fazla pahalılaştıran adres "
                    + "değişiklikleri; kurye durağı kapattıysa listelenmez.")
    public List<Suggestion> open() {
        return service.open();
    }

    @PostMapping("/{id}/postpone")
    @Operation(summary = "Yarına ertele",
            description = "Durak rotadan çıkar, kargo ertesi güne ertelenir, alıcıya bildirilir ve rota arka planda "
                    + "yeniden hesaplanır. Öneri sonuçlandıysa 409 SUGGESTION_CLOSED; kurye durağı kapattıysa "
                    + "409 STOP_ALREADY_CLOSED.")
    public Suggestion postpone(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.postpone(id, caller.id());
    }

    @PostMapping("/{id}/keep")
    @Operation(summary = "Bugün kalsın", description = "Öneri kapanır, rota değişmez. Sonuçlandıysa 409 SUGGESTION_CLOSED.")
    public Suggestion keep(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.keep(id, caller.id());
    }
}
