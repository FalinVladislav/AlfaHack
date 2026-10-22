package org.alfahack.features.insights.adapter.in.worker;

import org.alfahack.features.insights.port.HypothesisUseCases;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.insights.worker-enabled", havingValue = "true", matchIfMissing = true)
public class HypothesisWorker {

    private final HypothesisUseCases service;
    public HypothesisWorker(HypothesisUseCases service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.insights.poll-millis:2000}")
    public void tick() {
        service.processNext();
    }
}
