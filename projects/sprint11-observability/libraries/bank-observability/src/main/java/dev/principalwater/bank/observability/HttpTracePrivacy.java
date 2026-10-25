package dev.principalwater.bank.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

// Поиск получателя содержит login в query: трасса сохраняет маршрут, но не поисковый ввод.
final class HttpTracePrivacy implements ObservationFilter {
    private static final String[] URL_KEYS = {"http.url", "url.full", "http.uri"};

    @Override
    public Observation.Context map(Observation.Context context) {
        for (String key : URL_KEYS) {
            var value = context.getHighCardinalityKeyValue(key);
            if (value == null) continue;
            int query = value.getValue().indexOf('?');
            if (query >= 0) {
                context.removeHighCardinalityKeyValue(key);
                context.addHighCardinalityKeyValue(
                        KeyValue.of(key, value.getValue().substring(0, query)));
            }
        }
        return context;
    }
}
