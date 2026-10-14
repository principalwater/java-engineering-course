package dev.principalwater.store.controller;

import dev.principalwater.store.analytics.AnalyticsDashboard;
import dev.principalwater.store.analytics.AnalyticsPeriod;
import dev.principalwater.store.service.SellerService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

@Controller
@RequestMapping("/account/seller")
public class SellerController {
    private static final String ANALYTICS_PATH = "/account/seller/analytics";
    private final SellerService sellers;

    public SellerController(SellerService sellers) {
        this.sellers = sellers;
    }

    @GetMapping
    public Mono<String> seller(Model model) {
        return sellers.summary().map(view -> {
            model.addAttribute("seller", view);
            return "account/seller";
        });
    }

    @GetMapping("/analytics")
    public Mono<String> analytics(@RequestParam(required = false) Integer days, Model model) {
        return sellers.analytics(period(days)).map(view -> {
            model.addAttribute("analytics", view);
            model.addAttribute("periods", AnalyticsPeriod.values());
            model.addAttribute("analyticsBase", ANALYTICS_PATH);
            model.addAttribute("sellerAnalytics", true);
            return "analytics";
        });
    }

    @GetMapping(value = "/analytics/data", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Mono<AnalyticsDashboard> data(@RequestParam(required = false) Integer days) {
        return sellers.analytics(period(days));
    }

    @PostMapping("/analytics/sync")
    public Mono<String> sync(@ModelAttribute SyncInput input, WebSession session) {
        var selected = period(input.days());
        return sellers.sync().map(result -> {
            String notice = switch (result.status()) {
                case DELIVERED -> "Оплаченные заказы переданы в аналитику.";
                case IN_PROGRESS -> "Аналитика уже обновляется. Дождитесь завершения передачи.";
                case UNAVAILABLE -> "Аналитика пока недоступна. Оплаченные заказы остаются в очереди.";
            };
            session.getAttributes().put("notice", notice);
            return "redirect:" + ANALYTICS_PATH + "?days=" + selected.getDays();
        });
    }

    private AnalyticsPeriod period(Integer days) {
        return days == null ? AnalyticsPeriod.LAST_7_DAYS : AnalyticsPeriod.fromDays(days);
    }

    public record SyncInput(Integer days) {
    }
}
