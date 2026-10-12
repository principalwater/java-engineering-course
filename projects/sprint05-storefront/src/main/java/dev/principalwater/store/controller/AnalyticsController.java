package dev.principalwater.store.controller;

import dev.principalwater.store.analytics.AnalyticsDashboard;
import dev.principalwater.store.analytics.AnalyticsDashboardService;
import dev.principalwater.store.analytics.AnalyticsExporter;
import dev.principalwater.store.analytics.AnalyticsPeriod;
import dev.principalwater.store.analytics.AnalyticsQueue;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class AnalyticsController {
    private final AnalyticsQueue queue;
    private final ObjectProvider<AnalyticsDashboardService> dashboard;
    private final ObjectProvider<AnalyticsExporter> exporter;

    public AnalyticsController(AnalyticsQueue queue, ObjectProvider<AnalyticsDashboardService> dashboard,
                               ObjectProvider<AnalyticsExporter> exporter) {
        this.queue = queue;
        this.dashboard = dashboard;
        this.exporter = exporter;
    }

    @GetMapping("/analytics")
    public String get(@RequestParam(required = false) Integer days, Model model) {
        var period = period(days);
        var service = dashboard.getIfAvailable();
        AnalyticsDashboard view;
        if (service == null) {
            view = AnalyticsDashboard.inactive(AnalyticsDashboard.State.DISABLED, period, queue.status());
        } else {
            try {
                view = service.load(period);
            } catch (StoreException exception) {
                if (exception.getError() != StoreError.ANALYTICS_UNAVAILABLE) {
                    throw exception;
                }
                view = AnalyticsDashboard.inactive(AnalyticsDashboard.State.UNAVAILABLE, period, queue.status());
            }
        }
        model.addAttribute("analytics", view);
        model.addAttribute("periods", AnalyticsPeriod.values());
        return "analytics";
    }

    @PostMapping("/analytics/sync")
    public RedirectView sync(@RequestParam(required = false) Integer days, RedirectAttributes attributes) {
        var period = period(days);
        var delivery = exporter.getIfAvailable();
        if (delivery == null) {
            throw new StoreException(StoreError.ANALYTICS_UNAVAILABLE);
        }
        try {
            attributes.addFlashAttribute("notice", "Доставлено заказов: " + delivery.flush());
        } catch (StoreException exception) {
            if (exception.getError() != StoreError.ANALYTICS_UNAVAILABLE) {
                throw exception;
            }
            attributes.addFlashAttribute("notice", "Аналитика пока недоступна. Заказы остаются в очереди.");
        }
        return StoreRedirects.to("/analytics?days=" + period.getDays());
    }

    private AnalyticsPeriod period(Integer days) {
        return days == null ? AnalyticsPeriod.LAST_7_DAYS : AnalyticsPeriod.fromDays(days);
    }
}
