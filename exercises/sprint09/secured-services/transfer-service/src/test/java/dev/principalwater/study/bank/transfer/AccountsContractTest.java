package dev.principalwater.study.bank.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.StubFinder;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = AccountsContractTest.ClientContext.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureStubRunner(ids = "dev.principalwater.study.bank:accounts-service:1.0-SNAPSHOT:stubs",
        stubsMode = StubRunnerProperties.StubsMode.LOCAL)
class AccountsContractTest {
    @Configuration
    static class ClientContext { }

    @Autowired private StubFinder stubs;

    @Test
    void resolvesOwnerThroughThePublishedProviderContract() {
        var client = new AccountsClient(WebClient.builder()
                .baseUrl(stubs.findStubUrl("accounts-service").toString())
                .defaultHeaders(headers -> headers.setBearerAuth("consumer-token")).build());
        assertTrue(client.isOwner("ACC-001", "test-user"));
    }
}
