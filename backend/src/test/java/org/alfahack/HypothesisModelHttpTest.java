package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.alfahack.core.adapter.out.serialization.JacksonJsonCodec;
import org.alfahack.features.insights.adapter.out.http.HttpHypothesisModel;
import org.junit.jupiter.api.Test;

class HypothesisModelHttpTest {

    @Test
    void sendsAuthorizedBusinessContextThroughTheInternalAssistContract() throws Exception {
        var codec = new JacksonJsonCodec(new ObjectMapper());
        var received = new AtomicReference<Map<String, Object>>();
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/assist", exchange -> {
            received.set(codec.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = "{\"hypotheses\":[],\"questions\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        provider.start();
        try {
            var model = new HttpHypothesisModel("http://127.0.0.1:" + provider.getAddress().getPort(), 2, codec);
            var context = Map.<String, Object>of("candidate", Map.of("source_key", "cash-gap"), "calculations",
                Map.of("gap_amount", 120000));
            assertTrue(model.available());
            assertNotNull(model.explain(context).get("hypotheses"));
            assertEquals("growth_hypotheses", received.get().get("scenario"));
            assertEquals(context, received.get().get("context"));
            assertFalse(new HttpHypothesisModel("", 2, codec).available());
        } finally {
            provider.stop(0);
        }
    }
}
