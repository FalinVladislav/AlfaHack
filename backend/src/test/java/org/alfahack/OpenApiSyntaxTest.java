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
            @SuppressWarnings("unchecked")
            Map<String, Object> paths = (Map<String, Object>) document.get("paths");
            for (String path : new String[]{"/auth/sessions", "/insights", "/cashflow/summary", "/synthetic/profiles",
                    "/drafts/{id}/quotes", "/applications", "/applications/{id}/checks",
                    "/applications/{id}/contract/sign", "/applications/{id}/disburse", "/assistant/messages"})
                assertTrue(paths.containsKey(path), path);
        }
    }
}
