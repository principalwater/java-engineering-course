package dev.principalwater.bank.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;

public final class MessageCodec {
    // Размер ограничен также брокером; application parser не принимает произвольный JSON.
    private static final int MAX_CHARACTERS = 32768;
    private final ObjectMapper mapper;
    private final Validator validator;

    public MessageCodec(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public <T> T decode(String json, Class<T> type) {
        if (json == null || json.length() > MAX_CHARACTERS) throw new MessageRejected();
        try {
            T value = mapper.readValue(json, type);
            if (value == null || !validator.validate(value).isEmpty()) throw new MessageRejected();
            return value;
        } catch (JsonProcessingException failure) {
            throw new MessageRejected();
        }
    }
}
