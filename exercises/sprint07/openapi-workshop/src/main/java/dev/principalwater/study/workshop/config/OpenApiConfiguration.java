package dev.principalwater.study.workshop.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    public OpenAPI workshopOpenApi() {
        return new OpenAPI().info(new Info().title("API автомастерской").version("1.0.0")
                .description("Клиенты, заказы и состояние работ в автомастерской."));
    }
}
