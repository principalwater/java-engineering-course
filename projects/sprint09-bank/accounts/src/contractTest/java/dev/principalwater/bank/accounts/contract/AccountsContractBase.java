package dev.principalwater.bank.accounts.contract;

import dev.principalwater.bank.accounts.AccountsTestBase;
import dev.principalwater.bank.contract.Actor;
import dev.principalwater.bank.testing.BankFixtures;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

public abstract class AccountsContractBase extends AccountsTestBase {
    @BeforeEach
    void prepareContract() {
        seed("5808b517-d97b-4fdc-afc0-8f983fb28bc7", "alice", 0);
        seed("c4822d3a-33a3-4014-b19d-5a15e80ce7f3", "carol", 5000);
        seed("c465e4cf-4080-480f-9fdd-5eb550be9ff9", "bob", 0);
        RestAssuredMockMvc.mockMvc(mvc);
    }

    public String cashToken() {
        return "Bearer " + JWT.service("cash-service", "accounts-api", "accounts.cash");
    }

    public String transferToken() {
        return "Bearer " + JWT.service("transfer-service", "accounts-api", "accounts.transfer");
    }

    private void seed(String id, String login, long balance) {
        Actor actor = BankFixtures.actor(login);
        jdbc.update(
                """
                INSERT INTO account(id,issuer,subject,login,full_name,birth_date,balance_minor,version)
                VALUES (?,?,?,?,?,?,?,1)
                """,
                UUID.fromString(id),
                actor.issuer(),
                actor.subject(),
                actor.login(),
                login + " Example",
                Date.valueOf(LocalDate.of(1990, 6, 15)),
                balance);
    }
}
