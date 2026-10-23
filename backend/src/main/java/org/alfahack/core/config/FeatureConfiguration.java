package org.alfahack.core.config;

import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.core.transaction.UnitOfWork;
import org.alfahack.features.applications.application.Applications;
import org.alfahack.features.applications.port.ApplicationUseCases;
import org.alfahack.features.applications.port.ApplicationsRepository;
import org.alfahack.features.assistant.adapter.out.http.HttpLanguageModel;
import org.alfahack.features.assistant.application.Assistant;
import org.alfahack.features.assistant.port.AssistantRepository;
import org.alfahack.features.assistant.port.AssistantUseCases;
import org.alfahack.features.assistant.port.LanguageModel;
import org.alfahack.features.auth.application.AuthService;
import org.alfahack.features.auth.port.AuthRepository;
import org.alfahack.features.auth.port.AuthUseCases;
import org.alfahack.features.documents.application.Documents;
import org.alfahack.features.documents.port.DocumentContent;
import org.alfahack.features.documents.port.DocumentUseCases;
import org.alfahack.features.documents.port.DocumentsRepository;
import org.alfahack.features.drafts.application.Drafts;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.drafts.port.DraftsRepository;
import org.alfahack.features.forecast.application.Forecast;
import org.alfahack.features.forecast.port.ForecastRepository;
import org.alfahack.features.forecast.port.ForecastUseCases;
import org.alfahack.features.insights.application.BusinessInsights;
import org.alfahack.features.insights.port.InsightUseCases;
import org.alfahack.features.products.application.Products;
import org.alfahack.features.products.port.ProductCatalog;
import org.alfahack.features.scoring.adapter.out.memory.MockSources;
import org.alfahack.features.scoring.adapter.out.memory.SyntheticProfiles;
import org.alfahack.features.scoring.application.ScoringEngine;
import org.alfahack.features.scoring.port.ProfileCatalog;
import org.alfahack.features.scoring.port.RiskAssessment;
import org.alfahack.features.scoring.port.RiskSources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root: the only place where use cases choose concrete adapters.
 */
@Configuration
public class FeatureConfiguration {

    @Bean
    org.alfahack.features.scenarios.port.ScenarioModel scenarioModel(
        @Value("${app.model-api.url}") String url,
        @Value("${app.model-api.timeout-seconds}") int timeout,
        JsonCodec json
    ) {
        return new org.alfahack.features.scenarios.adapter.out.http.HttpScenarioModel(url, timeout, json);
    }

    @Bean
    org.alfahack.features.scenarios.port.ScenarioDialogUseCases scenarioDialogs(
        org.alfahack.features.scenarios.port.ScenarioDialogRepository repository,
        UnitOfWork transactions,
        DraftUseCases drafts,
        org.alfahack.features.finance.port.FinanceUseCases finance,
        org.alfahack.features.forecast.port.CreditScenarioUseCases scenarios,
        org.alfahack.features.scenarios.port.ScenarioModel model,
        JsonCodec json
    ) {
        return new org.alfahack.features.scenarios.application.ScenarioDialogs(repository, transactions, drafts,
            finance, scenarios, model, json);
    }

    @Bean
    org.alfahack.features.forecast.port.CreditScenarioUseCases creditScenarios(
        org.alfahack.features.finance.port.FinanceUseCases finance,
        ForecastRepository repository,
        ProductCatalog products,
        @Value("${app.demo-today}") String today
    ) {
        return new org.alfahack.features.forecast.application.CreditScenarios(finance, repository, products, today);
    }

    @Bean
    org.alfahack.features.goals.port.GoalUseCases goals(
        ProductCatalog products,
        LanguageModel model
    ) {
        return new org.alfahack.features.goals.application.GoalSelection(products, model);
    }

    @Bean
    org.alfahack.features.insights.port.HypothesisModel hypothesisModel(
        @Value("${app.model-api.url}") String url,
        @Value("${app.model-api.timeout-seconds}") int timeout,
        JsonCodec json
    ) {
        return new org.alfahack.features.insights.adapter.out.http.HttpHypothesisModel(url, timeout, json);
    }

    @Bean
    org.alfahack.features.insights.port.HypothesisUseCases hypotheses(
        org.alfahack.features.insights.port.HypothesisRepository repository,
        org.alfahack.features.finance.port.FinanceUseCases finance,
        ForecastUseCases forecast,
        ProductCatalog products,
        org.alfahack.features.insights.port.HypothesisModel model,
        JsonCodec json,
        @Value("${app.demo-today}") String today
    ) {
        return new org.alfahack.features.insights.application.Hypotheses(repository, finance, forecast, products, model,
            json, today);
    }

    @Bean
    org.alfahack.features.finance.port.FinanceUseCases finance(
        org.alfahack.features.finance.port.FinanceRepository repository
    ) {
        return new org.alfahack.features.finance.application.Finance(repository);
    }

    @Bean
    AuthUseCases auth(
        AuthRepository repository
    ) {
        return new AuthService(repository);
    }

    @Bean
    ProductCatalog products() {
        return new Products();
    }

    @Bean
    ProfileCatalog profiles() {
        return new SyntheticProfiles();
    }

    @Bean
    RiskSources sources(
        ProfileCatalog profiles
    ) {
        return new MockSources(profiles);
    }

    @Bean
    RiskAssessment scoring(
        ProfileCatalog profiles,
        RiskSources sources
    ) {
        return new ScoringEngine(profiles, sources);
    }

    @Bean
    org.alfahack.features.products.port.FinancingLimitUseCases financingLimits(
        org.alfahack.features.finance.port.FinanceUseCases finance,
        ForecastUseCases forecast,
        ProductCatalog products,
        @Value("${app.demo-today}") String today
    ) {
        return new org.alfahack.features.products.application.FinancingLimits(finance, forecast, products, today);
    }

    @Bean
    DraftUseCases drafts(
        UnitOfWork transactions,
        DraftsRepository repository,
        ProductCatalog products,
        org.alfahack.features.products.port.FinancingLimitUseCases limits,
        org.alfahack.features.goals.port.GoalUseCases goals,
        @Value("${app.demo-today}") String today
    ) {
        return new Drafts(transactions, repository, products, limits, goals, today);
    }

    @Bean
    ApplicationUseCases applications(
        UnitOfWork transactions,
        ApplicationsRepository repository,
        DraftUseCases drafts,
        ProductCatalog products,
        RiskAssessment scoring,
        ProfileCatalog profiles,
        JsonCodec json,
        @Value("${app.demo-today}") String today
    ) {
        return new Applications(transactions, repository, drafts, products, scoring, profiles, json, today);
    }

    @Bean
    DocumentUseCases documents(
        UnitOfWork transactions,
        DocumentsRepository repository,
        ApplicationUseCases applications,
        DocumentContent content
    ) {
        return new Documents(transactions, repository, applications, content);
    }

    @Bean
    ForecastUseCases forecast(
        ForecastRepository repository,
        @Value("${app.demo-today}") String today
    ) {
        return new Forecast(repository, today);
    }

    @Bean
    InsightUseCases insights(
        ProfileCatalog profiles,
        @Value("${app.demo-today}") String today
    ) {
        return new BusinessInsights(profiles, today);
    }

    @Bean
    LanguageModel model(
        @Value("${app.model-api.url}") String url,
        @Value("${app.model-api.timeout-seconds}") int timeout,
        JsonCodec json
    ) {
        return new HttpLanguageModel(url, timeout, json);
    }

    @Bean
    AssistantUseCases assistant(
        AssistantRepository repository,
        DraftUseCases drafts,
        ApplicationUseCases applications,
        LanguageModel model
    ) {
        return new Assistant(repository, drafts, applications, model);
    }
}
