package org.alfahack.features.insights.adapter.out.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.features.insights.port.HypothesisModel;

public class HttpHypothesisModel implements HypothesisModel {

    private final String url;
    private final Duration timeout;
    private final JsonCodec json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public HttpHypothesisModel(
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

    public Map<String, Object> explain(Map<String, Object> context) throws Exception {
        String message = "Разбери только переданную candidate. Верни ровно одну гипотезу с title, rationale, experiment, success_metric. "
            + "title — до 120 символов, формулируй как возможность или вопрос, без обещания результата. "
            + "Отдели факты из evidence от допущений. Не добавляй численных обещаний роста и новых условий кредита. "
            + "Для кассового разрыва объясни события и предложи сравнить перенос платежей с финансированием. "
            + "Для заявленной цели предложи проверку спроса и экономики до вложений. Укажи до трех недостающих сведений в questions. "
            + "Если оснований для гипотезы нет, верни hypotheses: [].";
        var request = HttpRequest.newBuilder(URI.create(url + "/v1/assist"))
            .timeout(timeout).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.write(Map.of(
                "scenario", "growth_hypotheses", "message", message, "context", context))))
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IllegalStateException("Inference unavailable");
        return json.read(response.body());
    }
}
