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
    ProfileCatalog profiles(org.springframework.jdbc.core.JdbcTemplate db) {
        return new org.alfahack.features.scoring.adapter.out.persistence.CompanyProfiles(db);
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
    DraftUseCases drafts(
        UnitOfWork transactions,
        DraftsRepository repository,
        ProductCatalog products,
        @Value("${app.demo-today}") String today
    ) {
        return new Drafts(transactions, repository, products, today);
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
        ForecastUseCases forecast,
        org.alfahack.features.finance.port.FinanceUseCases finance
    ) {
        return new BusinessInsights(forecast, finance);
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
        ForecastUseCases forecast,
        AssistantRepository repository,
        DraftUseCases drafts,
        ApplicationUseCases applications,
        LanguageModel model
    ) {
        return new Assistant(forecast, repository, drafts, applications, model);
    }
}
