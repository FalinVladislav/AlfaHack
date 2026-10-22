package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiSyntaxTest {

    @Test
    void contractParsesAndContainsMainJourney() throws Exception {
        try (var stream = Files.newInputStream(Path.of("docs/openapi.yaml"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> document = new Yaml().load(stream);
            assertEquals("3.0.3", document.get("openapi"));
            validateLocalReferences(document, document);
            @SuppressWarnings("unchecked")
            Map<String, Object> paths = (Map<String, Object>) document.get("paths");
            for (String path : new String[]{"/auth/sessions", "/insights", "/cashflow/summary", "/synthetic/profiles",
                    "/drafts/{id}/quotes", "/applications", "/applications/{id}/checks",
                    "/applications/{id}/contract/sign", "/applications/{id}/disburse", "/assistant/messages"})
                assertTrue(paths.containsKey(path), path);
        }
    }

    private void validateLocalReferences(
        Object value,
        Map<String, Object> document
    ) {
        if (value instanceof Map<?, ?> fields) {
            if (fields.get("$ref") instanceof String reference && reference.startsWith("#/")) {
                Object target = document;
                for (String part : reference.substring(2).split("/")) {
                    assertTrue(target instanceof Map<?, ?>, "Invalid reference: " + reference);
                    target = ((Map<?, ?>) target).get(part.replace("~1", "/").replace("~0", "~"));
                    assertNotNull(target, "Unresolved reference: " + reference);
                }
            }
            fields.values().forEach(child -> validateLocalReferences(child, document));
        } else
            if (value instanceof Iterable<?> children) {
                children.forEach(child -> validateLocalReferences(child, document));
            }
    }
}
