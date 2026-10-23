# Архитектура и состояния

```mermaid
flowchart LR
  Browser[React в браузере] -->|/api/v1| HTTP[HTTP-адаптеры фич]
  HTTP --> Ports[Входные порты сценариев]
  Ports --> Services[Бизнес-сервисы]
  Services --> Outbound[Выходные порты]
  Outbound --> JDBC[JDBC-адаптеры]
  JDBC --> DB[(PostgreSQL или H2)]
  Outbound --> Fixtures[Адаптер синтетических CSV]
  Outbound --> ModelAPI[HTTP-адаптер API моделей]
  ModelAPI -. опционально /v1/chat .-> Inference[Отдельный inference сервис]
```

Backend владеет денежными расчётами, переходами статусов, правами и сохранением данных. React отображает серверные ответы и отправляет подтверждённые действия. Сервис моделей получает контекст и сообщение, возвращает предлагаемую структуру, не изменяя БД напрямую. Провайдер и его настройки находятся в `inference/`; сервис включён в Compose и доступен бэкенду по `http://inference:8090`. Текущий чат использует `/v1/chat`, новые сценарии доступны через `/v1/assist`; контракт находится в `inference/openapi.json`.

Структура Java-пакетов:

```text
org.alfahack
├── CreditCabinetApplication       # точка входа
├── core
│   ├── domain                    # User, Session, Profile, Product, UploadedDocument
│   ├── errors                    # ошибки приложения без HTTP-статусов
│   ├── transaction                # интерфейс UnitOfWork
│   ├── serialization              # интерфейс JsonCodec
│   ├── adapter/in/web             # отображение ошибок в HTTP
│   ├── adapter/out/persistence    # преобразование JDBC-типов
│   ├── adapter/out/serialization  # Jackson-адаптер
│   ├── adapter/out/transaction    # Spring-адаптер транзакций
│   └── config                    # FeatureConfiguration, SeedData
└── features
    ├── auth
    ├── products
    ├── drafts
    ├── applications
    ├── documents
    ├── forecast
    ├── insights
    ├── scoring
    └── assistant
```

У фичи `port/` определяет входные интерфейсы сценариев и выходные интерфейсы хранения/интеграций, `application/` реализует сценарии, `adapter/out/persistence/jdbc/` содержит SQL, `adapter/in/web/` преобразует HTTP-запросы и вызывает входные порты. Папки создаются по необходимости: у расчёта продуктов нет репозитория БД, у аналитики нет записей в БД. У скоринга синтетические данные и mock-источники реализуют `ProfileCatalog` и `RiskSources` в `adapter/out/memory/`. У помощника выходной порт `LanguageModel` и адаптер `adapter/out/http/`.

Например, фича документов:

```text
features/documents/
├── port/
│   ├── DocumentUseCases.java
│   ├── DocumentsRepository.java
│   └── DocumentContent.java
├── application/Documents.java
└── adapter/
    ├── in/web/DocumentsController.java
    └── out/
        ├── persistence/jdbc/JdbcDocumentsRepository.java
        └── resource/SeedDocumentContent.java
```

Зависимости направлены к интерфейсам: HTTP → входной порт ← сервис → выходной порт ← JDBC/HTTP/ресурсный адаптер. Другие фичи также вызываются через входные порты. Для нового транспорта нужно реализовать адаптер, вызывающий тот же порт; сервисы не принимают servlet-объекты или `MultipartFile`. Они не импортируют Spring, JDBC, Jackson или сетевые классы. Ошибки классифицируются через `DomainException.Kind`; HTTP-статусы выбирает только HTTP-адаптер.

`FeatureConfiguration` связывает интерфейсы с реализациями. `UnitOfWork` задаёт транзакции подачи, скоринга, закрепления, решения, ответа клиента, подписи и выдачи. Подпись и выдача фиксируются раздельно: подписанная заявка сохраняется даже при сбое зачисления, а восстановление повторяет выдачу. Переходы защищены версией и текущим состоянием; ключи идемпотентности защищают повтор запросов. JDBC-даты преобразуются внутри адаптера. Существующие динамические проекции `Map<String,Object>` сохранены для совместимости API.

HTTP-авторизация обрабатывает cookie и CSRF в `HttpAuth`; проверку учётных данных, сессий и ролей выполняют сервис авторизации и доменная модель пользователя. Для другого транспорта вызывается `AuthUseCases.authenticate` со своим способом передачи токена.

`ArchitectureTest` проверяет направление зависимостей, `AssistantIntegrationTest` проверяет generic API моделей с локальным HTTP-стабом без реальной модели, `ScoringEngineTest` проверяет правила. `scripts/smoke.py` из корня прогоняет основной API-сценарий.

```mermaid
stateDiagram-v2
  [*] --> SUBMITTED
  SUBMITTED --> CHECKING: система запускает скоринг
  CHECKING --> NEEDS_INFO: недостаточно сведений
  CHECKING --> SCORING_ERROR: техническая ошибка
  SCORING_ERROR --> CHECKING: повтор оценки
  NEEDS_INFO --> CHECKING: сведения подтверждены
  CHECKING --> AWAITING_DECISION: рекомендация рассчитана
  AWAITING_DECISION --> IN_REVIEW: сотрудник взял в работу
  AWAITING_DECISION --> WITHDRAWN: клиент отозвал
  IN_REVIEW --> WITHDRAWN: клиент отозвал
  IN_REVIEW --> APPROVED: сотрудник одобрил
  IN_REVIEW --> COUNTEROFFER: сотрудник изменил условия
  IN_REVIEW --> REJECTED: сотрудник отказал
  APPROVED --> OFFER_ACCEPTED: клиент принимает
  COUNTEROFFER --> OFFER_ACCEPTED: клиент принимает
  APPROVED --> EXPIRED: срок истёк
  COUNTEROFFER --> EXPIRED: срок истёк
  APPROVED --> CLOSED: клиент отказался
  COUNTEROFFER --> CLOSED: клиент отказался
  OFFER_ACCEPTED --> SIGNED: подпись
  SIGNED --> DISBURSED: имитация выдачи
```

Главные сущности кредитного пути: `drafts`, `quotes`, `applications`, неизменяемые `application_requests`, `scoring_reports`, история `scoring_runs`, `company_risk_facts`, `application_offers`, `bank_decisions`, `application_responses`, `application_contracts`, `loans`, `loan_schedule`, `ledger_events`, `application_notifications`, `application_events`. Исходный запрос и предложение разделены. Сотрудник не выбирает тестовый профиль для смены результата: скоринг читает данные компании из БД и выдаёт рекомендацию. Окончательное решение записывается отдельно. Подробности — в [credit-policy.md](credit-policy.md).
