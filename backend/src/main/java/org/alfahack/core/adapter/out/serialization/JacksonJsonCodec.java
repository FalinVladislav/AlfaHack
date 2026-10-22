package org.alfahack.core.adapter.out.serialization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.alfahack.core.serialization.JsonCodec;
import org.springframework.stereotype.Component;

@Component
public class JacksonJsonCodec implements JsonCodec {

    private final ObjectMapper mapper;

    public JacksonJsonCodec(
        ObjectMapper mapper
    ) {
        this.mapper = mapper;
    }

    public String write(
        Object value
    ) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize data", ex);
        }
    }

    public Map<String, Object> read(
        String value
    ) {
        try {
            return mapper.readValue(value, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot deserialize data", ex);
        }
    }
}
