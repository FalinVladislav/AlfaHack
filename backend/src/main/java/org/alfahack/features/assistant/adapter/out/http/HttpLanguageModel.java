package org.alfahack.features.assistant.adapter.out.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.features.assistant.port.LanguageModel;

/**
 * Generic inference API client. Provider selection belongs to the inference
 * service.
 */
public class HttpLanguageModel implements LanguageModel {

    private final String url;
    private final Duration timeout;
    private final JsonCodec json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public HttpLanguageModel(
        String url,
        int timeoutSeconds,
        JsonCodec json
    ) {
        this.url = url.replaceAll("/$", "");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.json = json;
    }

    public boolean available() {
        return !url.isBlank();
    }

    public Map<String, Object> complete(
        String instructions,
        String message
    ) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url + "/v1/chat"))
            .timeout(timeout).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers
                .ofString(json.write(Map.of("instructions", instructions, "message", message))))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IllegalStateException("Inference API status " + response.statusCode());
        return json.read(response.body());
    }
}
