# Архитектура и состояния

```mermaid
flowchart LR
  Browser[React в браузере] -->|/api/v1| API[Spring Boot API]
  API --> DB[(PostgreSQL или локальная H2)]
  API --> Fixtures[(Подготовленные синтетические CSV)]
  API -->|/api/chat| Ollama[Локальная Ollama]
```

Backend владеет денежными расчётами, переходами статусов, правами и сохранением данных. React отображает серверные ответы и отправляет подтверждённые действия. Ollama получает текущие параметры черновика и сообщение, возвращает предлагаемую структуру, не изменяя БД напрямую.

```mermaid
stateDiagram-v2
  [*] --> SUBMITTED
  SUBMITTED --> CHECKING: сотрудник запускает проверку
  CHECKING --> NEEDS_INFO: недостаточно сведений
  NEEDS_INFO --> CHECKING: файл подтверждён
  CHECKING --> APPROVED: учебные правила пройдены
  CHECKING --> COUNTEROFFER: ограничение платежа
  CHECKING --> REJECTED: стоп-правило
  APPROVED --> OFFER_ACCEPTED: клиент принимает
  COUNTEROFFER --> OFFER_ACCEPTED: клиент принимает
  OFFER_ACCEPTED --> SIGNED: демоподпись
  SIGNED --> DISBURSED: демовыдача
```

Главные сущности: `companies`, `demo_accounts`, `account_credentials`, `sessions`, `drafts`, `quotes`, `applications`, `scoring_reports`, `info_requests`, `documents`, `loans`, `ledger_events`, `application_events`, `idempotency_records`, `assistant_proposals`. `quotes` сохраняет исходный расчёт, а `applications` — снимок запроса. `scoring_reports` хранит объяснимый результат пяти метрик. Журнал `application_events` фиксирует переходы. Семь отраслевых профилей создаются `SyntheticProfiles`, источники заменены `MockSources`; схема, пороги и JSON-примеры описаны в [credit-policy.md](credit-policy.md).
