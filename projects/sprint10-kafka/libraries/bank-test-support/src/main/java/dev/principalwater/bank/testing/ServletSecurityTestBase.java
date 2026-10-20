package dev.principalwater.bank.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

// HTTP security/slice-тесты используют ту же JWT-fixture без запуска PostgreSQL.
@AutoConfigureMockMvc
public abstract class ServletSecurityTestBase extends SecurityTestBase {
    @Autowired protected MockMvc http;
    @Autowired protected ObjectMapper mapper;
}
