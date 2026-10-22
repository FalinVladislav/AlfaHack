package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    @Test
    void useCasesAndDomainHaveNoFrameworkOrAdapterDependencies() throws Exception {
        Path root = Path.of("src/main/java/org/alfahack");
        int checked = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                boolean inner = relative.startsWith("core/domain/") || relative.startsWith("core/errors/")
                    || relative.contains("/application/") || relative.contains("/port/")
                    || relative.matches("core/(serialization|transaction)/[^/]+\\.java");
                if (!inner)
                    continue;
                checked++;
                String source = Files.readString(path);
                for (String forbidden : List.of("org.springframework", "jakarta.servlet", "java.net.", "java.sql.",
                    "com.fasterxml", ".adapter."))
                    assertFalse(source.contains(forbidden), relative + " depends on " + forbidden);
                assertFalse(source.matches("(?s).*\\nimport .*\\.application\\.[^;]+;.*"),
                    relative + " imports a concrete use case");
                assertFalse(source.matches("(?s).*\"(?:SELECT|INSERT|UPDATE|DELETE) .*"), relative + " contains SQL");
            }
        }
        assertTrue(checked >= 30, "Expected domain, application and port packages to be checked");
    }

    @Test
    void httpAdaptersDependOnUseCaseInterfaces() throws Exception {
        try (var paths = Files.walk(Path.of("src/main/java/org/alfahack/features"))) {
            for (Path path : paths
                .filter(
                    p -> p.toString().replace('\\', '/').contains("/adapter/in/web/") && p.toString().endsWith(".java"))
                .toList()) {
                String source = Files.readString(path);
                assertFalse(source.contains(".application."), path + " imports a concrete use case");
                assertFalse(source.contains(".adapter.out."), path + " imports an outbound adapter");
                assertFalse(source.matches("(?s).*\\nimport .*Repository;.*"), path + " bypasses a use case");
            }
        }
    }
}
