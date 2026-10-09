# Repository Guidelines

## Purpose

This file helps agents make correct, minimal, and verifiable changes to `apple-mfi-token`.
Prefer hard execution rules over general description.

Read `docs/DEVELOPMENT_PLAN.md` before starting any phase. It defines the tenant model, the
database schema, the five-stage token pipeline, and the phased delivery plan.

## Core Facts

This repository is a Maven multi-module SaaS backend plus a Vue frontend:

- `mfi-apple`: Apple MFI client. Multi-tenant mTLS client factory, request/response models,
  SSL context building, download-availability parsing. Holds no database or web concerns.
- `mfi-dao`: MyBatis-Plus persistence. `entity` (PO), `mapper`, tenant interceptor config,
  and all Flyway migrations under `src/main/resources/db/migration`.
- `mfi-service`: Spring Boot executable app. Controllers, services, RabbitMQ pipeline listeners,
  credential crypto, tenant context, sa-token auth config, Excel import/export.
- `web`: Vue 3 + Element Plus + Vite + TypeScript frontend. Uses **pnpm**, not npm.

Most changes should stay inside one module. Cross-module edits need a clear business reason.

### Non-negotiable product constraints

- **Multi-tenant SaaS.** Every business table carries `tenant_id`. Tenant isolation is enforced in
  SQL by `TenantLineInnerInterceptor` and cannot be disabled.
- **User management only, no permission management.** There are no roles, no permission points, no
  menu permissions, no data-scope rules. The single exception is the `owner_flag` boolean on
  `t_tenant_member`, which guards only: credential changes, tenant config changes, member removal,
  owner transfer, and token destruction. Do not expand this into a permission system.
- **Each tenant owns its Apple MFI certificate.** Clients are built per tenant by `MfiClientFactory`;
  there is no shared/global certificate.
- Independent of `galaplat-development-mes`: no shared database, no cross-service API calls,
  no Nacos, no `galaplat-*` internal jar dependencies.

## Environment

Verified on the development host:

- **JDK 17** (not 21). Keep `maven.compiler.release` at 17.
- Maven 3.9.12. `~/.m2/settings.xml` is **not active** (the file is named `settings.xml22`), so
  resolution goes to public Maven Central. Do not add internal Nexus URLs to the POM.
- **npm is broken** on this host (nvm multi-version mismatch, missing `npm-prefix.js`).
  Use `pnpm` for every frontend command. Do not write npm-only scripts into `web/package.json`.

## Must

- Read only the smallest set of files needed to act: root `pom.xml`, the target module `pom.xml`,
  the affected `controller/service/mapper/entity`, related DTO/VO classes, and
  `mfi-service/src/main/resources/application.yml` when config behavior matters.
- Make the smallest safe change. Keep edits inside one module unless the requirement clearly crosses
  boundaries.
- Follow the existing Java style: 4-space indentation, one top-level class per file, constructor
  injection, current Lombok usage.
- Add a Flyway migration for **every** schema change. Never edit an applied migration, and never
  change the database by hand.
- Every new business table must include `tenant_id` and must NOT be added to the global-table
  whitelist unless it is genuinely tenant-independent (`t_user`, `t_tenant`).
- Publish MQ messages only **after** the database transaction commits
  (`AFTER_COMMIT` synchronization). Never inside an open transaction.
- Use CAS-style conditional updates for mutable shared state, e.g.
  `UPDATE t_ppid SET remaining_quantity = ... WHERE id = ? AND remaining_quantity >= ?`.
  Never read-then-write PPID quantities.
- Verify each phase before starting the next:
  `mvn -q -DskipTests compile` from the repository root, and the phase acceptance items in
  `docs/DEVELOPMENT_PLAN.md` §11.
- After a verified feature commit, push the branch to `origin` without waiting for another
  confirmation (see Commit & PR → Push policy).
- State verification results clearly, especially when full validation is blocked by environment
  dependencies.

## Avoid

- Do not refactor unrelated code.
- Do not introduce new abstractions when an existing project pattern already fits.
- Do not add roles, permission tables, authorization annotations, or menu-driven access control.
- Do not change public request/response shapes, mapper contracts, SQL behavior, or MQ message
  payloads unless the task explicitly requires it.
- Do not put Apple certificates, private keys, master keys, session signing keys, database passwords,
  or one-off local debug configuration into the repository. Local overrides go in
  `application-local.yml` (gitignored) and `.env` (gitignored); only key names belong in
  `.env.example`.
- Sessions use sa-token's Redis dao (uuid tokens, state stored in Redis). Do not reintroduce
  `sa-token-jwt`, `StpLogicJwtForSimple`, or a JWT signing secret.
- Do not log `auth_entity_id`, `secret_key`, `accessory_uuid`, full PPID lists, or any credential
  material. Log counts and fingerprints instead.
- Do not hardcode business parameters that `t_tenant_config` already covers (quantity ratio, tokens
  per file, register batch size, max retry). Read them per tenant with a global default.
- Do not assume build failures are caused by your code before checking Maven Central reachability
  and local repository state.

## High-Risk Areas

Review these carefully before editing:

- `mfi-apple/src/main/java/com/galaplat/mfi/apple` — per-tenant mTLS construction and caching
- `mfi-service/src/main/java/com/galaplat/mfi/crypto` — credential encryption at rest
- `mfi-service/src/main/java/com/galaplat/mfi/pipeline` — five-stage MQ listeners, delay, retry, DLQ
- `mfi-service/src/main/java/com/galaplat/mfi/security` — `TenantContext`, owner checks, sa-token rules
- `mfi-dao/src/main/java/com/galaplat/mfi/dao/mybatis` — tenant SQL rewriting and its whitelist
- `mfi-dao/src/main/resources/db/migration` — schema history
- `mfi-service/src/main/java/com/galaplat/mfi/service/ppid` — PPID allocation and quantity decrement

These paths affect cross-tenant data isolation, async retries, PPID oversell prevention, and secret
handling. A mistake here leaks another tenant's data or double-spends PPID quantity at Apple.

## Verify

- Navigation and existing code paths: use `rg`.
- Backend:
  - `mvn -q -DskipTests compile`
  - `mvn test`
  - `mvn -pl mfi-service spring-boot:run`
- Frontend (from `web/`):
  - `pnpm install`
  - `pnpm typecheck`
  - `pnpm build`
- Infrastructure: `docker compose up -d mysql redis rabbitmq` before any run that touches the DB or MQ.
- Tenant isolation is a required test dimension: any new query needs an assertion that another
  tenant cannot see the row.
- Pipeline changes are verified against a stubbed Apple server, not real Apple credentials.

## Testing

Tests live under each module in Maven standard layout (`src/test/java`). Cover at minimum:
tenant isolation on every mapper, PPID allocation boundaries and concurrency, credential
round-trip encryption, MQ consumer idempotency under duplicate delivery, and the
transaction-rollback-does-not-publish-message guarantee.

## Commit & PR

Follow the existing concise Conventional Commit style used by the team, e.g.
`feat:Token申请(开启申请流程)`. Keep commits narrowly scoped to one phase or one fix.

### Push policy

The user has given **standing authorization to push**: once a feature or phase is committed and
verification passes, push the current branch to `origin` without asking again.

- Push only after `mvn -q -DskipTests compile` passes and that phase's acceptance items in
  `docs/DEVELOPMENT_PLAN.md` §11 are met. This authorization does **not** cover pushing unverified,
  failing, or half-finished work.
- One feature = one commit = one push. Do not batch several phases into one push.
- Push the current branch only. Never `--force`, never `--no-verify`, never amend or rebase a commit
  that already exists on the remote.
- Before every commit, run `git status` and confirm nothing unexpected is staged. Credentials,
  `.env`, `application-local.yml`, `*.pem`, `*.key`, and any certificate material must never reach
  the remote. `.gitignore` is not a substitute for this check.
- The remote is GitHub (`origin`). If the repository is public, treat anything already committed as
  permanently public — never stage real Apple certificates or private keys, even "temporarily".
- When the remote has moved ahead, integrate it with a merge commit; do not rewrite shared history.
- If a push fails for a non-code reason (auth, network, protected branch), report it and keep working
  locally. Do not change git config or the remote URL to force it through.

PRs should summarize the business change, list affected modules, note Flyway migration or config
impact, describe how tenant isolation was verified, and state explicitly what could not be verified
in the local environment.
