package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:documentation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "app.model-api.url="
})
@AutoConfigureMockMvc
class ApiDocumentationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void servesTheCanonicalContractWithoutAuthentication() throws Exception {
        mvc.perform(get("/api-docs/openapi.yaml"))
            .andExpect(status().isOk())
            .andExpect(content().bytes(Files.readAllBytes(Path.of("docs/openapi.yaml"))));
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void servesSwaggerAndBundledAssetsWithoutExternalServices() throws Exception {
        String html = mvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(html.contains("@swagger-ui.version@"));
        var assets = Pattern.compile("(?:href|src)=\"(/webjars/[^\"]+)\"").matcher(html);
        int checked = 0;
        while (assets.find()) {
            mvc.perform(get(assets.group(1))).andExpect(status().isOk());
            checked++;
        }
        assertEquals(3, checked);
        mvc.perform(get("/swagger-ui/swagger-initializer.js"))
            .andExpect(status().isOk());
    }

    @Test
    void conventionalSwaggerUrlsRedirectToTheUi() throws Exception {
        for (String path : new String[]{"/swagger-ui", "/swagger-ui/", "/swagger-ui.html"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        }
    }
}
