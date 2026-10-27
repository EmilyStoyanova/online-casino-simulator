# ADR-0001: Modular monolith in a monorepo

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

The MVP has nine functional areas (auth, users, wallet, roulette, learning, rewards, responsible
play, statistics, admin) and is developed in 12 weeks by a small team. Wallet settlement and game
rounds must be **atomic** across modules (a spin debits, settles and records the round in one
database transaction).

## Decision

- Build **one Spring Boot application** split into business modules (`com.casino.<module>`) with
  explicit dependency rules (see `docs/architecture.md`).
- Use **one PostgreSQL database**; each module owns its tables.
- Keep backend, frontend, infrastructure and documentation in **one repository** (monorepo).
- Do not introduce microservices, Kubernetes, message brokers or Redis in the first version.

## Consequences

- ✅ Cross-module atomicity uses plain local transactions — no distributed transactions or sagas.
- ✅ One build, one deployment, simple local setup and debugging.
- ✅ Clear module boundaries keep a later extraction into services possible.
- ⚠️ Boundaries are enforced by convention until an automated module test is added
  (Spring Modulith or ArchUnit — to be decided in a follow-up ADR).
- ⚠️ The whole backend scales as one unit, which is sufficient for the expected load.
