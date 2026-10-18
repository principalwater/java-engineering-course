package dev.principalwater.bank.notifications;

import dev.principalwater.bank.testing.JdbcServiceTestBase;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.config.import=classpath:bank-common.properties")
public abstract class NotificationsTestBase extends JdbcServiceTestBase {
    @Override
    protected void resetDatabase() {
        jdbc.execute("TRUNCATE bank_notification");
    }
}
