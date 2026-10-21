package dev.principalwater.bank.bi;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class BiApplication {
    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context =
                SpringApplication.run(BiApplication.class, args)) {
            Environment environment = context.getEnvironment();
            try (SupersetApi api =
                    new SupersetApi(
                            URI.create(environment.getRequiredProperty("bank.bi.url")),
                            Path.of(
                                    environment.getProperty(
                                            "bank.bi.ca-file", "/run/secrets/tls_ca.pem")))) {
                api.login(environment.getRequiredProperty("superset_admin_password"));
                api.importAssets(
                        SupersetAssets.archive(
                                environment.getRequiredProperty("bank.bi.clickhouse-host")),
                        environment.getRequiredProperty("analytics_bi_password"));
                api.grantDatasets();
                api.verifyCharts();
                System.out.println(
                        "Bank analytics dashboards are ready; stable asset identities were preserved");
            }
        }
    }
}
