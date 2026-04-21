# trasck-test

Java Playwright automation tests for the Trasck backend and Vite React frontend.

## Scope

This repository is the external end-to-end and contract-style test suite for the Trasck stack. It should cover behavior that spans the backend, frontend, browser authentication, generated API contract, workers, imports, exports, automation, notifications, and AI agent flows.

Current starter coverage:

- Backend API smoke test for `GET /api/trasck/health`.
- Backend API smoke test for `GET /api/v1/auth/csrf`.
- Backend API smoke tests for setup endpoint reachability, unauthenticated current-user denial, optional login/current-user cookie and Bearer sessions, and local OpenAPI availability.
- Optional first-run setup bootstrap coverage that creates a disposable local stack through `/api/v1/setup` and proves a second setup attempt is rejected.
- Authenticated backend API coverage for workspace/project security policy, public project and public work-item read boundaries, workspace/project role management, personal and service token scope behavior, system-admin/audit/export reads, configuration/custom-field/screen/reporting CRUD, work item collaboration/activity, attachment byte upload/download, workspace and work-item label cleanup, reporting history/snapshot reads and snapshot execution controls, program CRUD/project assignment/dashboard summary, team-scoped dashboard/filter/view/report-query lists, repository connections, invitation list/cancellation, workspace-admin human user list/creation/removal, team/planning/board/release/roadmap resources, notification preferences/defaults plus direct and automation-created notification read coverage, dashboards, saved filters, saved views, favorites, and recent items when local credentials plus workspace/project IDs are configured or setup bootstrap is enabled against an empty stack.
- Committed OpenAPI route coverage baseline at `src/test/resources/backend-route-coverage.tsv`, including `coverageOwner` for covered rows, plus generated route inventory output under `test-results/api/backend-route-inventory.tsv` so backend API gaps can be closed route by route. The current baseline tracks 250 covered backend routes, 0 ordinary planned routes, and 144 planned high-risk routes.
- Optional sample-data fixture support that stays separate from first-run setup bootstrap.
- Frontend browser smoke tests for the route shell, core navigation, Workspace Settings member/invitation workflow, program/portfolio management, public-read settings, and public project/work-item preview route.

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
- `TRASCK_E2E_WORKSPACE_ID`: optional workspace ID used by authenticated workspace/project API coverage.
- `TRASCK_E2E_PROJECT_ID`: optional project ID used by authenticated project API coverage.
- `TRASCK_E2E_ALLOW_SETUP`: set to `true` only for disposable local stacks where the suite may call first-run `/api/v1/setup`. Defaults to `false`.
- `TRASCK_E2E_SEED_SAMPLE_DATA`: set to `true` only for tests that explicitly request optional browser workflow sample data after setup/login. Defaults to `false`.

Do not commit `.env`; use `.env.example` for safe defaults only.

The authenticated smoke tests are skipped unless both login variables are set or `TRASCK_E2E_ALLOW_SETUP=true` can create a disposable first-run stack. Workspace/project API tests also require `TRASCK_E2E_WORKSPACE_ID` and `TRASCK_E2E_PROJECT_ID` unless setup bootstrap creates those IDs in the current run. Use a disposable local test user with the least permission needed for the scenario being exercised; current broad API coverage expects project/workspace admin permissions so it can create and clean up its own test resources through public APIs where cleanup APIs exist.

## Local Stack

Run the backend and frontend with their normal local settings, keeping the backend on port `6100` and the frontend on port `8080`. The smoke suite assumes the backend owns PostgreSQL, Redis, Maildev, and any other service dependencies; `trasck-test` only drives HTTP and browser behavior from outside the application.

For deterministic local runs, start the stack from a known database state, create or reuse a disposable test user, set the optional login and workspace/project variables in `.env`, then run `mvn test` from this repository. For a brand-new disposable database, set `TRASCK_E2E_ALLOW_SETUP=true` instead; the suite will create a Playwright admin/workspace/project through `/api/v1/setup`, reuse those IDs in-memory for the same Maven run, and verify that a second setup call returns conflict. Setup bootstrap intentionally remains minimal. Broader browser workflow data is created only through the separate optional sample-data fixture when `TRASCK_E2E_SEED_SAMPLE_DATA=true` and a test calls that fixture. The API tests create uniquely named temporary resources and delete the resources they create through Trasck HTTP APIs where public cleanup APIs exist; they do not use direct database access.

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
- `test-results/api/backend-route-inventory.tsv`: generated OpenAPI route inventory with `covered`, `planned-high-risk`, and `planned` statuses.
- `src/test/resources/backend-route-coverage.tsv`: committed coverage baseline. The OpenAPI route inventory test fails when a backend route is added without an explicit baseline status, and covered rows must name their owning Java Playwright test in `coverageOwner`. The current baseline tracks 250 covered backend routes, 0 ordinary planned routes, and 144 planned high-risk routes.
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
2. API coverage for every implemented backend route, including auth, projects, work items, planning, boards, dashboards, saved filters/views, notifications, automation, imports, exports, system admin, workspace/project security policy, and AI agent surfaces.
3. Browser workflow coverage for the same feature families using the real frontend screens.
4. Security regression coverage for tenant isolation, authorization denial, public-read boundaries, token/callback validation, redaction, upload/export access, and production-facing hardening controls.
5. Full-stack scenario coverage that creates fresh data, performs realistic user workflows, validates durable side effects, and leaves enough diagnostics for bug triage.
