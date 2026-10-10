# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Windows / PowerShell, Java 21, Spring Boot 4.1 (Gradle wrapper). No lint task is configured.

```powershell
.\gradlew.bat compileJava --no-daemon
.\gradlew.bat test --no-daemon
.\gradlew.bat test --no-daemon --tests "*EmailChangeCompletionIntegrationTests"             # one class
.\gradlew.bat test --no-daemon --tests "*EmailChangeCompletionIntegrationTests.tokenIsSingleUse"   # one method
.\gradlew.bat bootRun        # needs PostgreSQL; see env vars below
```

- Tests are `@SpringBootTest` + MockMvc against in-memory **H2** (PostgreSQL mode, with `CREATE DOMAIN TIMESTAMPTZ` in the JDBC URL). They need no external DB.
- Runtime config comes from env vars with defaults in `application.yaml` (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SIGNUP_TERMS_VERSION`, `SIGNUP_PRIVACY_VERSION`, per-API `*_RATE_LIMIT_*`/`*_MAX_ATTEMPTS`). Signup is rejected until the terms/privacy version env vars are set.
- `ddl-auto: none` in production: schema changes are hand-written SQL in `docs/sql/Axx_*.sql`, applied manually to the DB before deploy. The app never runs them, and tests build the schema from the entities.

## Source of truth and workflow

- Requirements live in `docs/*.pdf` (API 명세서, 기능명세서/요구사항분석서, DB 설계서, AI 서버 설계서). Work is organized by API ID (`A01`–`A16`...). Each implemented API gets `docs/Axx-*.md` and, if the schema changed, `docs/sql/Axx_*.sql`.
- Extract PDF text with `pdftotext -enc UTF-8 -layout <file> <out.txt>`; without `-enc UTF-8` Korean text comes out garbled.
- `claude.note` holds the working rules: report (don't implement) when documents conflict, no features beyond the spec, no large refactors, explain which files changed and why, and say plainly if tests were not run.
- `README.md` is the original architecture guide, but its response-format section is outdated (see below). Trust the code.

## Architecture

Package root `com.personalab.vectoract.vector_act_was`: `global/` (shared infra) and `domain/<name>/{presentation,business,persistence}`. Only `member` exists so far. Flow is Controller → Service → Repository; Controllers never touch Repositories.

**Response envelope.** Actual shape is `{"success":true,"data":...,"message":"OK","error":null}` (see `ApiResponse`/`ErrorResponse`), not the `status/timestamp` shape shown in README. Errors are `BusinessException(ErrorCode.X)`; `ErrorCode` carries the HTTP status.

**Per-controller exception handlers.** The auth/token-style controllers (`PasswordReset*`, `EmailChange*`, ...) define their own `@ExceptionHandler`s (validation → `VALIDATION_ERROR`, DB-down → 503, anything else → 500) instead of relying only on `GlobalExceptionHandler`, so token/password values never leak through default error output. New controllers in this area copy that pattern.

**Security chain (`global/config/SignupSecurityConfig`).** One `SecurityFilterChain` decides everything:
- `publicRequests` = endpoints that need no Bearer (CSRF fetch, signup, login, refresh, logout, password-reset request/complete, email-change completion). `AccessTokenAuthenticationFilter` skips these; everything else requires a valid Bearer Access JWT.
- CSRF is enforced for all POSTs via a custom `ExpiringCsrfTokenRepository` (token from `GET /api/auth/csrf`, bound to a session cookie). Bearer-only user APIs (`/api/users/me...`, `/api/auth/reauth`) are explicitly listed in `ignoringRequestMatchers`. When adding an endpoint, decide both lists deliberately. Being public and being CSRF-exempt are independent.
- CSRF/auth failures are rendered as JSON in the filter layer, not by `GlobalExceptionHandler`.

**One-time tokens.** `auth_one_time_tokens` backs REAUTH (A09, 5 min), PASSWORD_RESET (A11/A12, 15 min), EMAIL_CHANGE (A13/A14, 15 min, with `new_email`). Only the SHA-256 hash is stored (`RefreshTokenGenerator.generate()/hash()`, 256-bit Base64URL). Use `consumeIfUsable` (a conditional UPDATE) to consume atomically; `invalidateByUserAndType` / `invalidateAll` to revoke.

**Concurrency convention.** Services lock the user row first (`UserRepository.lockById` / `lockByEmail`, pessimistic write) so login, refresh, password change, withdrawal, and token flows serialize per user. Multi-step changes (consume token + change data + revoke refresh tokens) run in one `@Transactional` method and rely on rollback when any step throws. DB unique violations (`SQLState 23505`) are translated to `EMAIL_ALREADY_EXISTS`.

**Refresh tokens.** Family-based rotation with reuse detection (`RefreshTokenRepository`). Password change and email-change completion revoke all of a user's refresh tokens. Already-issued Access JWTs stay valid until they expire (accepted policy).

**Account lifecycle.** Withdrawal is soft delete (`WITHDRAWN`, `purge_at` = +7 days), then `MemberPurgeScheduler` hard-deletes. Withdrawn users' emails still count as taken. Users not `ACTIVE` generally get 404 or an "invalid token" error, never a distinguishable status.

**Rate limiting.** `LoginRateLimiter` is an in-memory, single-instance sliding window, instantiated separately per controller with its own `auth.*-rate-limit.*` config so counters don't mix. Exceeding the limit throws `RATE_LIMITED` (429).

**Email delivery.** Outbound mail goes through interfaces (`PasswordResetDelivery`, `EmailChangeDelivery`) called by `@TransactionalEventListener`-style listeners after commit. No SMTP implementation is wired yet. When no bean exists, the token is still stored and the API returns success. Tests mock the delivery bean (`@MockitoBean`).
