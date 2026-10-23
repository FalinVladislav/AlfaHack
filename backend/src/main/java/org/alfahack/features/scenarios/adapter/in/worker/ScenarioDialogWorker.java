package org.alfahack.features.scenarios.adapter.in.worker;

import org.alfahack.features.scenarios.port.ScenarioDialogUseCases;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.scenarios.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ScenarioDialogWorker {

    private final ScenarioDialogUseCases service;
    public ScenarioDialogWorker(ScenarioDialogUseCases service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.scenarios.poll-millis:1000}")
    public void tick() {
        service.processNext();
    }
}
