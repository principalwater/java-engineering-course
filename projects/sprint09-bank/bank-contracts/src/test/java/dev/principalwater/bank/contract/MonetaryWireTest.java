package dev.principalwater.bank.contract;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MonetaryWireTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"12.5", "1e2", "\"100\"", "true", "9223372036854775808"})
    void rejectsLossyOrCoercedAmountsBeforeBusinessValidation(String amount) {
        assertThrows(JsonMappingException.class, () -> mapper.readValue(json(amount), CashRequest.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 100, BankLimits.MAX_AMOUNT_MINOR})
    void preservesIntegerMinorUnitsExactly(long amount) throws Exception {
        assertEquals(amount, mapper.readValue(json(Long.toString(amount)), CashRequest.class).amountMinor());
    }

    private String json(String amount) {
        return "{\"operationId\":\"" + UUID.randomUUID() + "\",\"type\":\"DEPOSIT\",\"amountMinor\":" + amount + "}";
    }
}
