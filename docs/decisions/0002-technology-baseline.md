# ADR-0002: Technology and dependency baseline

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

The implementation plan fixes the technology families (Java 21 + Spring Boot, React + TypeScript +
Vite, Tailwind, PostgreSQL, Flyway, Docker, JUnit/Mockito/Testcontainers, Vitest/RTL, Playwright,
GitHub Actions). Concrete versions must be stable, mutually compatible and supported for the whole
project timeline.

## Decision

| Component | Version | Rationale |
|---|---|---|
| Java | 21 LTS (Temurin) | Required by the plan; LTS |
| Spring Boot | 4.1.x | Latest GA line; 3.5.x has left open-source support |
| Spring Boot BOM-managed libraries | Hibernate 7.4, Flyway 12.4, Testcontainers 2.0, JUnit Jupiter 6, Mockito 5, PostgreSQL JDBC 42.7 | Versions curated and tested together by Spring Boot; not overridden |
| springdoc-openapi | 3.2.0 | Line built for Spring Boot 4.1 |
| PostgreSQL | 17 (`postgres:17.11-alpine`) | Matches the managed production offering (Neon) |
| Maven | 3.9.16 via Maven Wrapper (checksum-pinned) | Reproducible builds without a local Maven install |
| Node.js | 24 LTS (≥ 22.12 supported) | Required by Vite 8 |
| React | 19.3 | Current stable |
| TypeScript | 6.0 | TypeScript 7 (native compiler) is not yet supported by `typescript-eslint` |
| Vite / Vitest | 8.x / 5.x | Current stable, mutually compatible |
| Tailwind CSS | 4.x via `@tailwindcss/vite` | No PostCSS configuration needed |
| ESLint | 10 + `typescript-eslint`, React Hooks and React Refresh plugins | Default tooling of the Vite React template |

Notes:

- **JUnit:** Spring Boot 4.1 ships JUnit Jupiter 6, the direct successor of JUnit 5 with the same
  programming model. It is used as managed rather than downgrading the BOM.
- **Spring Security** is part of the stack but is added in Week 2 together with authentication, to
  avoid a throw-away "permit all" configuration in Week 1.
- **Spring Boot Actuator** (part of Spring Boot) provides `/actuator/health` for container health
  checks on the hosting platform; only `health` and `info` are exposed.
- Versions are upgraded deliberately (patch updates routinely, minor/major via a PR with green CI).

## Consequences

- ✅ One BOM governs most backend versions, minimising compatibility issues.
- ⚠️ Spring Boot 4 / Jackson 3 / Testcontainers 2 changed some package names compared with older
  tutorials (e.g. `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`,
  `org.testcontainers.postgresql.PostgreSQLContainer`).
