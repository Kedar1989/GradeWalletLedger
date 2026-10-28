package com.project.GradeWalletLedger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final RiskFlagExplainer riskFlagExplainer;

    public ReconciliationScheduler(
            ReconciliationService reconciliationService,
            RiskFlagExplainer riskFlagExplainer) {
        this.reconciliationService = reconciliationService;
        this.riskFlagExplainer = riskFlagExplainer;
    }

    @Scheduled(cron = "${app.reconciliation.cron:0 0 2 * * *}", zone = "UTC")
    public void runNightly() {
        var report = reconciliationService.run();
        LOGGER.info("Nightly reconciliation finished: runId={}, status={}, problems={}",
                report.runId(), report.status(), report.problemCount());
        int explained = riskFlagExplainer.explainTodaysFlags();
        LOGGER.info("Nightly risk flag explanations saved: {}", explained);
    }
}