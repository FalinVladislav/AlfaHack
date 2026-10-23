# Сторонние компоненты и имитации

- Spring Boot и зависимости Maven: лицензии указаны в их опубликованных POM.
- Swagger UI (WebJar `org.webjars:swagger-ui`): [Apache License 2.0](https://github.com/swagger-api/swagger-ui/blob/main/LICENSE).
- Alfa Core Components Text, Tabs, Input, Select и Button (`@alfalab/core-components-*`) и glyph-иконки (`@alfalab/icons-glyph`): MIT; версии закреплены в frontend/package-lock.json.
- jsdom: MIT, используется только для DOM-тестов frontend.
- React и Vite: лицензии указаны в `frontend/package-lock.json` и пакетах.
- Знак Альфа-Банка на экране входа: SVG по [ссылке, предоставленной пользователем](https://alfabank.servicecdn.ru/icons/logo_alfa-bank-sign-square_m_color.svg).
- PostgreSQL и контейнерные образы: используются для локального запуска по лицензиям соответствующих проектов.
- Ollama не входит в сборку проекта. Выбранная пользователем модель загружается отдельно; перед распространением проверьте лицензию конкретных весов.

ФНС, БКИ, финансовая сводка, проверка документов, кредитное решение, договор, подпись и выдача реализованы как учебные локальные имитации. Денежные значения и правила каталога не являются тарифами или решением Альфа-Банка.
