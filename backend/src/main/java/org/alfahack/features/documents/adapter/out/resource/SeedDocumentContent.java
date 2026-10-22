package org.alfahack.features.documents.adapter.out.resource;

import java.io.IOException;
import java.util.Locale;
import org.alfahack.features.documents.port.DocumentContent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SeedDocumentContent implements DocumentContent {

    public byte[] statement(
        String company
    ) {
        var resource = new ClassPathResource("seed-documents/" + company.toLowerCase(Locale.ROOT) + "_statement.csv");
        if (!resource.exists())
            return null;
        try (var stream = resource.getInputStream()) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read seed statement", ex);
        }
    }
}
