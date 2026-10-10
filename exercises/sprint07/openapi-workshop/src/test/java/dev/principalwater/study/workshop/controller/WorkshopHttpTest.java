package dev.principalwater.study.workshop.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.principalwater.study.workshop.generated.model.Customer;
import dev.principalwater.study.workshop.generated.model.CustomerInput;
import dev.principalwater.study.workshop.generated.model.Order;
import dev.principalwater.study.workshop.generated.model.OrderInput;
import dev.principalwater.study.workshop.generated.model.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopHttpTest {
    @Autowired
    private WebTestClient client;

    @Test
    void customerOrdersCompleteTheirLifecycleWithoutLosingAnotherOrder() {
        var customer = customer("Lifecycle Customer");
        client.get().uri("/api/customers/{id}", customer.getId()).exchange().expectStatus().isOk()
                .expectBody(Customer.class).isEqualTo(customer);
        client.get().uri("/api/customers").exchange().expectStatus().isOk()
                .expectBodyList(Customer.class).value(rows -> assertThat(rows).contains(customer));

        var input = new OrderInput("Volvo V60", "Oil change", OrderStatus.PENDING,
                new BigDecimal("1234.56789"), customer.getId());
        var created = createOrder(input);
        var preserved = createOrder(new OrderInput("Saab 9-3", "Inspection", OrderStatus.PENDING,
                BigDecimal.ZERO, customer.getId()));
        assertThat(created.getId()).isPositive().isNotEqualTo(preserved.getId());
        assertThat(created.getPrice()).isEqualByComparingTo("1234.56789");
        client.get().uri("/api/orders").exchange().expectStatus().isOk()
                .expectBodyList(Order.class).value(rows -> assertThat(rows).contains(created, preserved));

        input.status(OrderStatus.IN_PROGRESS).price(new BigDecimal("1300.25"));
        client.put().uri("/api/orders/{id}", created.getId()).bodyValue(input).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.id").isEqualTo(created.getId())
                .jsonPath("$.status").isEqualTo("In Progress");
        input.status(OrderStatus.COMPLETED);
        client.put().uri("/api/orders/{id}", created.getId()).bodyValue(input).exchange()
                .expectStatus().isOk();
        client.get().uri("/api/orders/{id}", created.getId()).exchange().expectStatus().isOk()
                .expectBody(Order.class).value(row -> {
                    assertThat(row.getStatus()).isEqualTo(OrderStatus.COMPLETED);
                    assertThat(row.getPrice()).isEqualByComparingTo("1300.25");
                    assertThat(row.getCustomerId()).isEqualTo(customer.getId());
                });

        client.delete().uri("/api/orders/{id}", created.getId()).exchange().expectStatus().isNoContent()
                .expectBody().isEmpty();
        client.get().uri("/api/orders/{id}", created.getId()).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/orders/{id}", created.getId()).exchange().expectStatus().isNotFound();
        client.put().uri("/api/orders/{id}", created.getId()).bodyValue(input).exchange()
                .expectStatus().isNotFound();
        client.get().uri("/api/orders/{id}", preserved.getId()).exchange().expectStatus().isOk()
                .expectBody(Order.class).isEqualTo(preserved);
    }

    @Test
    void invalidRequestsAndMissingCustomerCannotChangeAnExistingOrder() {
        var customer = customer("Validation Customer");
        var original = createOrder(new OrderInput("Toyota Corolla", "Diagnostics", OrderStatus.PENDING,
                new BigDecimal("500"), customer.getId()));
        var invalidOrders = List.of(
                "{\"carModel\":\"Car\",\"serviceType\":\"Check\",\"status\":\"Pending\",\"price\":-1,\"customerId\":" + customer.getId() + "}",
                "{\"carModel\":\"Car\",\"serviceType\":\"Check\",\"status\":\"Unknown\",\"price\":0,\"customerId\":" + customer.getId() + "}",
                "{\"carModel\":\"  \",\"serviceType\":\"Check\",\"status\":\"Pending\",\"price\":0,\"customerId\":" + customer.getId() + "}",
                "{\"carModel\":\"Car\",\"serviceType\":\"Check\",\"status\":null,\"price\":0,\"customerId\":" + customer.getId() + "}",
                "{\"carModel\":\"Car\",\"serviceType\":\"Check\",\"status\":\"Pending\",\"price\":0}"
        );
        for (String input : invalidOrders) {
            client.put().uri("/api/orders/{id}", original.getId()).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(input).exchange().expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
        var missingCustomer = new OrderInput("Other car", "Repair", OrderStatus.COMPLETED,
                BigDecimal.TEN, Long.MAX_VALUE);
        client.put().uri("/api/orders/{id}", original.getId()).bodyValue(missingCustomer).exchange()
                .expectStatus().isNotFound().expectBody().jsonPath("$.status").isEqualTo(404);
        client.post().uri("/api/orders").bodyValue(missingCustomer).exchange().expectStatus().isNotFound();
        client.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isBadRequest();
        client.post().uri("/api/customers").bodyValue(new CustomerInput("Name", "123", "invalid"))
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/api/customers/0").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/customers/{id}", Long.MAX_VALUE).exchange().expectStatus().isNotFound();
        client.get().uri("/api/orders/{id}", original.getId()).exchange().expectStatus().isOk()
                .expectBody(Order.class).isEqualTo(original);
    }

    @Test
    void runningApplicationPublishesAllEightOperationsAndSwaggerUi() {
        client.get().uri("/v3/api-docs").exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).value(document -> {
                    assertThat(document.at("/info/title").asText()).isEqualTo("API автомастерской");
                    var paths = document.get("paths");
                    assertThat(paths.size()).isEqualTo(4);
                    assertThat(paths.get("/api/customers").has("get")).isTrue();
                    assertThat(paths.get("/api/customers").has("post")).isTrue();
                    assertThat(paths.get("/api/customers/{id}").has("get")).isTrue();
                    assertThat(paths.get("/api/orders").has("get")).isTrue();
                    assertThat(paths.get("/api/orders").has("post")).isTrue();
                    for (String method : List.of("get", "put", "delete")) {
                        assertThat(paths.get("/api/orders/{id}").has(method)).isTrue();
                    }
                    assertThat(document.at("/components/schemas/Order/properties/id/format").asText())
                            .isEqualTo("int64");
                });
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class).value(html -> assertThat(html).contains("Swagger UI"));
    }

    private Customer customer(String name) {
        var result = client.post().uri("/api/customers")
                .bodyValue(new CustomerInput(name, "+79990000000", "study@example.com"))
                .exchange().expectStatus().isCreated().expectBody(Customer.class).returnResult();
        var body = result.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.getId()).isPositive();
        assertThat(result.getResponseHeaders().getLocation().getPath()).isEqualTo("/api/customers/" + body.getId());
        return body;
    }

    private Order createOrder(OrderInput input) {
        var result = client.post().uri("/api/orders").bodyValue(input).exchange()
                .expectStatus().isCreated().expectBody(Order.class).returnResult();
        var body = result.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(result.getResponseHeaders().getLocation().getPath()).isEqualTo("/api/orders/" + body.getId());
        return body;
    }
}
