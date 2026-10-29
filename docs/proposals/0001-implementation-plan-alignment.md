# Change proposal 0001 — Align the implementation plan with the accepted architecture

- **Status:** Proposed. The PDF is **not** modified until this proposal is approved.
- **Date:** 2026-10-29
- **Affects:** `docs/OnlineCasinoSimulator_ImplementationPlan.pdf`
- **Schedule impact:** none. The 12-week roadmap, the stage boundaries and the MVP scope stay
  unchanged.

## Why

Since the plan was written, accepted decisions have refined *how* parts of the plan are delivered:
- [ADR-0002](../decisions/0002-technology-baseline.md): technology baseline;
- [ADR-0004](../decisions/0004-design-first-api.md): design-first API and layered packages;
- [ADR-0005](../decisions/0005-architecture-rules-archunit.md): ArchUnit;
- [ADR-0006](../decisions/0006-session-authentication-security.md) (proposed): session security;
- the approved Week 2 plan.

The business requirements, acceptance criteria and timeline are not affected. This proposal lists
every place where the PDF no longer matches, so the plan document stays the single reference for
the mentorship.

Proposed texts are given in Bulgarian, the language of the PDF.

## Summary

| # | PDF section | Type | Change |
|---|---|---|---|
| C1 | §2 Technologies | Clarification | JUnit 5 → JUnit Jupiter (version 6, successor); add Spring Session JDBC, ArchUnit, React Router; OpenAPI as design-first contract |
| C2 | §3 Architecture | Addition | Design-first API contract; layered packages inside each module |
| C3 | §4 Week 2 | Addition | Contract first; CSRF; login/registration throttling; session lifetime limits; architecture tests |
| C4 | §4 Week 5 | Clarification | Routing exists since Week 2; Week 5 builds the casino navigation on it |
| C5 | §4 Week 6 | Clarification | `user_preferences` holds UI settings (sound, reduced motion) |
| C6 | §4 Week 10 | Clarification | Admin actions (disable user, role change) **must** revoke the user's sessions (Spring Session principal index); this is a known Week 2 limitation |
| C7 | §4 Week 11 | Addition | Contract and architecture tests are part of QA |
| C8 | §4 Week 12 | Addition | Same-site API routing, `__Host-` cookies, trusted proxies |
| C9 | §5 Database | Correction | New tables `interests`, `user_interests`, `spring_session*`, `auth_throttle`; meaning of `user_preferences` and `user_sessions` |
| C10 | §6 REST API | Addition | `GET /system/status`, `GET /auth/csrf`, `GET /interests`; contract file reference |
| C11 | §7 Acceptance criteria | Addition | API contract and architecture rules |
| C12 | §8 Project structure | Correction | Package structure inside modules; `openapi.yaml`; `docs/plans`, `docs/proposals` |
| C13 | §9 Workflow | Addition | API contract design step before backend implementation |

## Detailed changes

### C1 — §2 Technologies

| Current | Proposed |
|---|---|
| "Backend тестване: JUnit 5, Mockito, Testcontainers" | "Backend тестване: JUnit Jupiter (JUnit 6 — наследник на JUnit 5, управляван от Spring Boot), Mockito, Testcontainers, ArchUnit" |
| "API документация: springdoc-openapi / Swagger UI" | "API договор и документация: OpenAPI 3.1 (`openapi.yaml`, design-first), springdoc-openapi / Swagger UI" |
| — (new row) | "Сесии: Spring Session JDBC (PostgreSQL)" |
| "Frontend: React + TypeScript + Vite" | "Frontend: React + TypeScript + Vite, React Router" |

Reason: ADR-0002 (Spring Boot 4.1 ships JUnit Jupiter 6), ADR-0004, ADR-0005 and ADR-0006.

### C2 — §3 Architecture (new subsection after 3.2)

> **3.2.1. Организация на модулите и API договор**
>
> Всеки backend модул използва еднаква слоеста структура: `controller` (REST), `service` (бизнес
> логика), `repository` (достъп до данни), `entity` (JPA), `dto` (заявки и отговори), `config` и
> `exception`. Модулите комуникират единствено чрез своите `service` и `dto` пакети. Правилата се
> проверяват автоматично с ArchUnit.
>
> REST API се проектира първо (design-first) във файла `openapi.yaml`, който е единственият
> източник на истина. Реализацията се проверява автоматично спрямо договора.

### C3 — §4 Week 2

Add after task 10:

> 11. Проектиране на API договора за автентикацията и профила в `openapi.yaml` преди реализацията.
> 12. CSRF защита за всички заявки, променящи състояние, с обновяване на токена след вход и изход.
> 13. Защита от brute-force атаки без възможност за злонамерено заключване на чужди акаунти.
> 14. Ограничение на сесиите: 30 минути неактивност и максимална продължителност от 12 часа.
> 15. Автоматични тестове на архитектурните правила и на съответствието с API договора.

The expected result stays unchanged.

### C4 — §4 Week 5, task 1

| Current | Proposed |
|---|---|
| "Проектиране на началната страница и навигацията." | "Проектиране на началната страница и навигацията на казиното (маршрутизацията с React Router е въведена в Седмица 2)." |

### C5 — §4 Week 6, tasks 3 and 7

Add to tasks 3 and 7: "Настройките се съхраняват в `user_preferences`."

### C6 — §4 Week 10, task 8

| Current | Proposed |
|---|---|
| "Защита на административните API endpoints чрез роли." | "Защита на административните API endpoints чрез роли; деактивирането на потребител и смяната на роля прекратяват активните му сесии." |

### C7 — §4 Week 11, new task

> 12. Проверка на съответствието между реализацията и API договора и на архитектурните правила.

### C8 — §4 Week 12, task 6

| Current | Proposed |
|---|---|
| "Конфигуриране на HTTPS, CORS, cookies и настройките за сигурност." | "Конфигуриране на HTTPS, маршрутизиране на API през същия сайт като frontend-а (заради блокирането на third-party cookies), `__Host-` cookies, доверени proxy сървъри, CORS и настройките за сигурност." |

### C9 — §5 Database design

Change in the table:

| Table | Current purpose | Proposed purpose |
|---|---|---|
| `user_preferences` | "Потребителски интереси и предпочитания" | "Потребителски предпочитания за интерфейса (звук, анимации)" |
| `user_sessions` | "История и продължителност на сесиите" | "История и продължителност на игровите сесии" |

New rows:

| Table | Purpose |
|---|---|
| `interests` | "Каталог на образователните интереси" |
| `user_interests` | "Избраните от потребителя интереси" |
| `spring_session`, `spring_session_attributes` | "HTTP сесии (Spring Session JDBC)" |
| `auth_throttle` | "Ограничаване на опитите за вход и регистрация" |

Reason: interests are a many-to-many relation (Week 2 plan §6). HTTP sessions and play sessions
are different concepts.

### C10 — §6 REST API

New rows:

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/v1/system/status` | "Състояние на платформата" |
| GET | `/api/v1/auth/csrf` | "Получаване на CSRF токен" |
| GET | `/api/v1/interests` | "Каталог на интересите" |

Add below the table: "Пълната спецификация на API се поддържа във файла `openapi.yaml`
(design-first), който е източникът на истина за всички endpoints."

### C11 — §7 Acceptance criteria, new rows

| Component | Proposed criterion |
|---|---|
| "API договор" | "Реализацията съответства на `openapi.yaml` (автоматично проверено)" |
| "Архитектура" | "Модулните граници и слоевете се спазват (ArchUnit)" |

### C12 — §8 Project structure

Replace the backend part of the tree with:

```
backend/
├── src/main/java/com/casino/
│   ├── auth/ user/ wallet/ game/ learning/ responsibleplay/ admin/
│   │   └── controller/ service/ repository/ entity/ dto/ config/ exception/
│   └── common/   (config/, exception/, system/)
├── src/main/resources/
│   ├── db/migration/
│   └── static/openapi.yaml
└── pom.xml
```

and add `docs/plans/` and `docs/proposals/` to the `docs/` part. Also note that the repository is
named `online-casino-simulator` (the plan shows `online-casino/`).

### C13 — §9 Workflow

| Current step 3 | Proposed steps 3–4 |
|---|---|
| "3. Реализиране на backend логиката и тестовете." | "3. Проектиране или обновяване на API договора (`openapi.yaml`) и неговото одобрение. 4. Реализиране на backend логиката и тестовете спрямо договора." |

The remaining steps are renumbered.

## Not changed

- MVP scope (§1.3/§1.4), target audience, responsible-play restrictions and the "not a treatment"
  statement.
- The 12-week timeline (§4, §13) and the Week 4 priority milestone (§14).
- Hosting providers (Cloudflare Pages, Render, Neon). C8 only refines how they are combined.

## Approval and application

1. Review this proposal.
2. After approval, the PDF is updated from its source document (or a new PDF version is produced)
   by the plan owner. The current PDF stays in the repository history.
3. This proposal's status becomes "Applied", with the date of the new PDF version.
