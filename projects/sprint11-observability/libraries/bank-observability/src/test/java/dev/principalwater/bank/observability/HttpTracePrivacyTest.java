package dev.principalwater.bank.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

class HttpTracePrivacyTest {
    @Test
    void receiverSearchRetainsOnlyTheRouteInBothHttpSemanticConventions() {
        Observation.Context context = new Observation.Context();
        context.addHighCardinalityKeyValue(
                KeyValue.of("http.url", "https://accounts/api/recipients?search=private-login"));
        context.addHighCardinalityKeyValue(
                KeyValue.of("url.full", "https://accounts/api/recipients?search=private-login"));
        context.addHighCardinalityKeyValue(
                KeyValue.of("http.uri", "https://accounts/api/recipients?search=private-login"));
        context.addHighCardinalityKeyValue(KeyValue.of("unrelated", "retained"));
        new HttpTracePrivacy().map(context);
        for (String key : new String[] {"http.url", "url.full", "http.uri"}) {
            assertThat(context.getHighCardinalityKeyValue(key).getValue())
                    .isEqualTo("https://accounts/api/recipients");
        }
        assertThat(context.getHighCardinalityKeyValue("unrelated").getValue())
                .isEqualTo("retained");
    }
}
