package dev.principalwater.store.service;

import dev.principalwater.store.analytics.AnalyticsDashboard;
import dev.principalwater.store.analytics.AnalyticsDashboardService;
import dev.principalwater.store.analytics.AnalyticsPeriod;
import dev.principalwater.store.analytics.DeliveryResult;
import dev.principalwater.store.dao.SellerQueries;
import dev.principalwater.store.model.SellerView;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SellerService {
    private final SellerQueries sellers;
    private final AnalyticsDashboardService dashboard;

    public SellerService(SellerQueries sellers, AnalyticsDashboardService dashboard) {
        this.sellers = sellers;
        this.dashboard = dashboard;
    }

    public Mono<SellerView> summary() {
        return sellers.summary();
    }

    public Mono<AnalyticsDashboard> analytics(AnalyticsPeriod period) {
        return dashboard.loadForSeller(period);
    }

    public Mono<DeliveryResult> sync() {
        return dashboard.sync();
    }
}
