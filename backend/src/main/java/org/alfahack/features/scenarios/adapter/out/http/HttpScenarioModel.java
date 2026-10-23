package org.alfahack.features.scenarios.adapter.out.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.features.scenarios.port.ScenarioModel;

public class HttpScenarioModel implements ScenarioModel {

    private final String url;
    private final Duration timeout;
    private final JsonCodec json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public HttpScenarioModel(
        String url,
        int seconds,
        JsonCodec json
    ) {
        this.url = url.replaceAll("/$", "");
        this.timeout = Duration.ofSeconds(seconds);
        this.json = json;
    }

    public Map<String, Object> complete(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools
    ) throws Exception {
        if (url.isBlank())
            throw new IllegalStateException("Model is not configured");
        var request = HttpRequest.newBuilder(URI.create(url + "/v1/tools")).timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.write(Map.of("messages", messages, "tools", tools))))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 128000)
            throw new IllegalStateException("Invalid inference response");
        return json.read(response.body());
    }
}
