package dev.principalwater.store.controller;

import dev.principalwater.store.analytics.AnalyticsDashboardService;
import dev.principalwater.store.analytics.AnalyticsPeriod;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class AnalyticsController {
    private final AnalyticsDashboardService dashboard;

    public AnalyticsController(AnalyticsDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/analytics")
    public String get(@RequestParam(required = false) Integer days, Model model) {
        var period = period(days);
        model.addAttribute("analytics", dashboard.load(period));
        model.addAttribute("periods", AnalyticsPeriod.values());
        return "analytics";
    }

    @PostMapping("/analytics/sync")
    public RedirectView sync(@RequestParam(required = false) Integer days, RedirectAttributes attributes) {
        var period = period(days);
        var result = dashboard.sync();
        attributes.addFlashAttribute("notice", result.isPresent() ? "Доставлено заказов: " + result.getAsInt()
                : "Аналитика пока недоступна. Заказы остаются в очереди.");
        return StoreRedirects.to("/analytics?days=" + period.getDays());
    }

    private AnalyticsPeriod period(Integer days) {
        return days == null ? AnalyticsPeriod.LAST_7_DAYS : AnalyticsPeriod.fromDays(days);
    }
}
