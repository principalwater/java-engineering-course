package dev.principalwater.store.controller;

import dev.principalwater.store.analytics.AnalyticsDashboard;
import dev.principalwater.store.analytics.AnalyticsDashboardService;
import dev.principalwater.store.analytics.AnalyticsPeriod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

@Controller
public class AnalyticsController {
    private final AnalyticsDashboardService dashboard;

    public AnalyticsController(AnalyticsDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping({"/analytics", "/profile/analytics"})
    public Mono<String> get(@RequestParam(required = false) Integer days, Model model) {
        return dashboard.load(period(days)).map(view -> {
            model.addAttribute("analytics", view);
            model.addAttribute("periods", AnalyticsPeriod.values());
            return "analytics";
        });
    }

    @GetMapping(value = "/profile/analytics/data", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Mono<AnalyticsDashboard> data(@RequestParam(required = false) Integer days) {
        return dashboard.load(period(days));
    }

    @PostMapping({"/analytics/sync", "/profile/analytics/sync"})
    public Mono<String> sync(@ModelAttribute SyncInput input, WebSession session) {
        var selected = period(input.days());
        return dashboard.sync().map(result -> {
            String notice = switch (result.status()) {
                case DELIVERED -> "Доставлено заказов: " + result.deliveredOrders();
                case IN_PROGRESS -> "Очередь уже обновляется. Показатели появятся после завершения доставки.";
                case UNAVAILABLE -> "Аналитика пока недоступна. Заказы остаются в очереди.";
            };
            session.getAttributes().put("notice", notice);
            return "redirect:/profile/analytics?days=" + selected.getDays();
        });
    }

    private AnalyticsPeriod period(Integer days) {
        return days == null ? AnalyticsPeriod.LAST_7_DAYS : AnalyticsPeriod.fromDays(days);
    }

    public record SyncInput(Integer days) {
    }
}
