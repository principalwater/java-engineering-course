package dev.principalwater.bank.testing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// Servlet/JDBC-тесты получают HTTP и БД; чистые JDBC-тесты не загружают MVC ради наследования.
@AutoConfigureMockMvc
public abstract class JdbcServiceTestBase extends PostgresTestBase {
    @Autowired protected MockMvc http;
}
