package dev.principalwater.study.actuator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;

public class AvailabilityDemo {
    public static void main(String[] args) throws Exception {
        SpringApplication application = new SpringApplication(ActuatorApplication.class);
        application.addListeners(new LifecycleLogger());
        try (var context = application.run("--server.port=0",
                "--management.endpoint.health.probes.enabled=true")) {
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            URI readiness = URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness");
            try (HttpClient client = HttpClient.newHttpClient()) {
                verify(client, readiness, HttpStatus.OK, Status.UP);
                // После run() Boot уже опубликовал ACCEPTING_TRAFFIC и не перезапишет наш отказ.
                AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
                verify(client, readiness, HttpStatus.SERVICE_UNAVAILABLE, Status.OUT_OF_SERVICE);
                AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
                verify(client, readiness, HttpStatus.OK, Status.UP);
            }
            System.out.println("Readiness: UP -> OUT_OF_SERVICE -> UP, HTTP check passed");
        }
    }

    private static void verify(HttpClient client, URI endpoint, HttpStatus http, Status health)
            throws Exception {
        var response = client.send(HttpRequest.newBuilder(endpoint).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String status = new ObjectMapper().readTree(response.body()).get("status").asText();
        if (response.statusCode() != http.value() || !status.equals(health.getCode())) {
            throw new AssertionError("Unexpected readiness: HTTP " + response.statusCode() + ", " + status);
        }
    }
}
