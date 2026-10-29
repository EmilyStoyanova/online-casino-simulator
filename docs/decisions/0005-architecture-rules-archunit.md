# ADR-0005: Architecture rules with ArchUnit

- **Status:** Accepted
- **Date:** 2026-10-29 (decision approved 2026-10-28)
- **Related:** [ADR-0001](0001-modular-monolith.md), which left boundary enforcement open, and
  [ADR-0004](0004-design-first-api.md), which defined the layer names.

## Context

ADR-0001 enforces module boundaries only by convention "until an automated module test is added
(Spring Modulith or ArchUnit)". ADR-0004 introduced the layer packages `controller`, `service`,
`repository`, `entity`, `dto`, `config` and `exception` inside each module. From Week 2 on, modules
call each other (`auth → user`), so boundary violations become possible.

## Decision

Use **ArchUnit** (`com.tngtech.archunit:archunit-junit5`, test scope only) for automated
architecture rules in `ModuleArchitectureTest`. The rules run with the unit tests (`./mvnw test`)
and in CI:

1. `common` does not depend on business modules.
2. Business modules are free of dependency cycles.
3. Cross-module access only via the target module's `service`, `dto` and `exception` packages.
4. Layer direction inside a module: `controller → service → repository → entity`, with no reverse
   dependencies.
5. Placement of controllers, controller advice, entities, repositories and configuration classes.
6. Controllers use DTOs only (no repositories, no entities).
7. DTOs do not depend on entity classes.
8. Sensitive types (e.g. `UserCredentials`) are accessed only by explicitly allowed modules.
9. Spring Security configuration API only in `auth.config`.
10. No field injection.

The concrete rule list for Week 2 is in `docs/plans/week-02.md` §9.

## Alternatives considered

- **Spring Modulith (2.1.x):** verifies boundaries by convention and offers module documentation,
  module-scoped tests and an event publication registry. It requires `@NamedInterface` annotations
  for the layer packages and more conventions while modules are still small. It is reconsidered when
  module-scoped tests or reliable event publication are needed, for example for Week 10 audit events.
- **Convention and code review only:** not repeatable; rejected.

## Consequences

- ✅ Test-only dependency; no runtime impact.
- ✅ Explicit, readable rules that fail the build with a precise message.
- ⚠️ Rules are written by hand and must be extended when new modules or exceptions appear.
