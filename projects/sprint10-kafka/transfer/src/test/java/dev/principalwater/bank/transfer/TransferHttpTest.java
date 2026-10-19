package dev.principalwater.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.principalwater.bank.contract.InternalOperation;
import dev.principalwater.bank.runtime.error.ApiErrors;
import dev.principalwater.bank.runtime.security.ApiJwtConfiguration;
import dev.principalwater.bank.runtime.workflow.MoneyWorkflow;
import dev.principalwater.bank.testing.BankFixtures;
import dev.principalwater.bank.testing.ServletSecurityTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@WebMvcTest(
        controllers = TransferController.class,
        properties = {
            "spring.config.import=classpath:bank-common.properties",
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"
        })
@ContextConfiguration(classes = TransferController.class)
@Import({TransferSecurity.class, TransferService.class, ApiJwtConfiguration.class, ApiErrors.class})
class TransferHttpTest extends ServletSecurityTestBase {
    @MockitoBean private MoneyWorkflow workflow;

    @Test
    void verifiedUserOwnsCommandAndSuccessfulResultPreservesOriginalRequestId() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflow.execute(any())).thenReturn(BankFixtures.succeeded(id, 400));
        http.perform(
                        post("/api/transfers")
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + JWT.user("alice-sub", "alice", "transfer.write"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"operationId\":\"%s\",\"recipientId\":\"c465e4cf-4080-480f-9fdd-5eb550be9ff9\",\"amountMinor\":400}"
                                                .formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("operationId").value(id.toString()));
        ArgumentCaptor<InternalOperation> commands =
                ArgumentCaptor.forClass(InternalOperation.class);
        verify(workflow).execute(commands.capture());
        assertThat(commands.getValue().actor().issuer()).isEqualTo(JWT.issuer());
        assertThat(commands.getValue().actor().subject()).isEqualTo("alice-sub");
        assertThat(commands.getValue().operationId()).isEqualTo(id);
    }

    @Test
    void anotherOperationScopeDoesNotCreateIntent() throws Exception {
        http.perform(
                        post("/api/transfers")
                                .header(
                                        "Authorization",
                                        "Bearer " + JWT.user("alice-sub", "alice", "cash.write"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"operationId\":\"%s\",\"recipientId\":\"c465e4cf-4080-480f-9fdd-5eb550be9ff9\",\"amountMinor\":400}"
                                                .formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(workflow);
    }

    @Test
    void pendingResultHasAcceptedStatusAndNoInventedBalance() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflow.execute(any())).thenReturn(BankFixtures.pending(id));
        http.perform(
                        post("/api/transfers")
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + JWT.user("alice-sub", "alice", "transfer.write"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"operationId\":\"%s\",\"recipientId\":\"c465e4cf-4080-480f-9fdd-5eb550be9ff9\",\"amountMinor\":400}"
                                                .formatted(id)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("status").value("PENDING"))
                .andExpect(jsonPath("balanceMinor").doesNotExist());
    }
}
