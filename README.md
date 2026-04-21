# trasck-test

Java Playwright automation tests for the Trasck backend and Vite React frontend.

## Scope

This repository is the external end-to-end and contract-style test suite for the Trasck stack. It should cover behavior that spans the backend, frontend, browser authentication, generated API contract, workers, imports, exports, automation, notifications, and AI agent flows.

Current starter coverage:

- Backend API smoke test for `GET /api/trasck/health`.
- Backend API smoke test for `GET /api/v1/auth/csrf`.
- Backend API smoke tests for setup endpoint reachability, unauthenticated current-user denial, optional login/current-user cookie and Bearer sessions, and local OpenAPI availability.
- Frontend browser smoke test for the route shell and core navigation.

## Prerequisites

- Java 21
- Maven
- Running Trasck backend on `http://localhost:6100`
- Running Trasck frontend on `http://localhost:8080`
- PostgreSQL, Redis, and Maildev running through the Trasck backend Docker Compose/local runtime setup when the backend profile needs them

Install Playwright browsers locally once:

```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

## Configuration

Copy the example env file for local overrides:

```bash
cp .env.example .env
```

Supported variables:

- `TRASCK_BACKEND_BASE_URL`: backend base URL. Defaults to `http://localhost:6100`.
- `TRASCK_FRONTEND_BASE_URL`: frontend base URL. Defaults to `http://localhost:8080`.
- `TRASCK_E2E_BROWSER`: `chromium`, `firefox`, or `webkit`. Defaults to `chromium`.
- `TRASCK_E2E_HEADLESS`: browser headless mode. Defaults to `true`.
- `TRASCK_E2E_TIMEOUT_MS`: service readiness timeout. Defaults to `30000`.
- `TRASCK_E2E_LOGIN_IDENTIFIER`: optional existing Trasck login identifier for authenticated smoke tests.
- `TRASCK_E2E_LOGIN_PASSWORD`: optional existing Trasck password for authenticated smoke tests.

Do not commit `.env`; use `.env.example` for safe defaults only.

The authenticated smoke tests are skipped unless both login variables are set. Use a disposable local test user with the least permission needed for the scenario being exercised.

## Local Stack

Run the backend and frontend with their normal local settings, keeping the backend on port `6100` and the frontend on port `8080`. The smoke suite assumes the backend owns PostgreSQL, Redis, Maildev, and any other service dependencies; `trasck-test` only drives HTTP and browser behavior from outside the application.

For deterministic local runs, start the stack from a known database state, create or reuse a disposable test user, set the optional login variables in `.env`, then run `mvn test` from this repository.

## Run Tests

Run the full Playwright suite:

```bash
mvn test
```

Run only API tests:

```bash
mvn test -Dgroups=api
```

Run only frontend browser tests:

```bash
mvn test -Dgroups=frontend
```

Run through Docker, targeting Trasck services on the host:

```bash
docker compose run --rm trasck-test
```

Artifacts are written under `test-results/`:

- `test-results/api`: response snippets with sensitive headers and token-shaped body fields redacted.
- `test-results/screenshots`: browser screenshots captured by smoke tests.
- `test-results/traces`: Playwright trace ZIP files for browser runs.
- `test-results/report.html`: Extent report output when report listeners are enabled by future suites.

## Layout

- `src/test/java/com/strangequark/trascktest/api`: backend API and contract tests.
- `src/test/java/com/strangequark/trascktest/e2e`: browser-driven frontend tests.
- `src/test/java/com/strangequark/trascktest/config`: environment-backed test configuration.
- `src/test/java/com/strangequark/trascktest/support`: shared Playwright/browser/runtime helpers.

## Coverage Roadmap

Build this suite outward in thin, stable layers:

1. Smoke coverage for backend health, frontend shell, setup, login, current user, and CSRF/cookie behavior.
2. API coverage for every implemented backend route, including auth, projects, work items, planning, boards, dashboards, saved filters/views, notifications, automation, imports, exports, system admin, workspace security policy, and AI agent surfaces.
3. Browser workflow coverage for the same feature families using the real frontend screens.
4. Security regression coverage for tenant isolation, authorization denial, public-read boundaries, token/callback validation, redaction, upload/export access, and production-facing hardening controls.
5. Full-stack scenario coverage that creates fresh data, performs realistic user workflows, validates durable side effects, and leaves enough diagnostics for bug triage.
