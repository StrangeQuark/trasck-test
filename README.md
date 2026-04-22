# trasck-test

Java Playwright automation tests for the Trasck backend and Vite React frontend.

## Scope

This repository is the external end-to-end and contract-style test suite for the Trasck stack. It should cover behavior that spans the backend, frontend, browser authentication, generated API contract, workers, imports, exports, automation, notifications, and AI agent flows.

Current starter coverage:

- Backend API smoke test for `GET /api/trasck/health`.
- Backend API smoke test for `GET /api/v1/auth/csrf`.
- Backend API smoke tests for setup endpoint reachability, unauthenticated current-user denial, optional login/current-user cookie and Bearer sessions, and local OpenAPI availability.
- Optional first-run setup bootstrap coverage that creates a disposable local stack through `/api/v1/setup` and proves a second setup attempt is rejected.
- Authenticated backend API coverage for workspace/project security policy, public project/work-item/comment/attachment read boundaries with signed public attachment downloads, workspace/project role management, personal and service token scope behavior, system-admin/audit/export reads and mutations, domain-event replay, configuration/custom-field/screen/reporting CRUD, work item collaboration/activity, attachment byte upload/download, workspace and work-item label cleanup, reporting history/snapshot reads and snapshot execution controls, program CRUD/project assignment/dashboard summary, team-scoped dashboard/filter/view/report-query lists, repository connections, invitation list/cancellation, workspace-admin human user list/creation/removal, team/planning/board/release/roadmap resources, notification preferences/defaults plus direct and automation-created notification read coverage, automation rule/job/worker settings and worker run/health retention coverage, signed local-receiver-backed webhook delivery coverage with dual-secret rotation overlap and durable delivery key IDs, high-risk import lifecycle/review-export/report coverage, auth/logout/register/OAuth unsafe-input coverage, Maildev dry-run email delivery coverage, provider-neutral agent provider/profile/credential/task/callback/worker-token/dispatch-attempt coverage including Codex/Claude CLI runtime provider readiness, dashboards, saved filters, saved views, favorites, and recent items when local credentials plus workspace/project IDs are configured or setup bootstrap is enabled against an empty stack.
- Committed OpenAPI route coverage baseline at `src/test/resources/backend-route-coverage.tsv`, including `coverageOwner` for covered rows, plus generated route inventory output under `test-results/api/backend-route-inventory.tsv` so backend API gaps can be closed route by route. The current baseline tracks 399 covered backend routes, 0 ordinary planned routes, and 0 planned high-risk routes.
- Optional sample-data fixture support that stays separate from first-run setup bootstrap, plus per-test API seed creation for real-backend browser workflows.
- Opt-in managed production-like backend stack coverage that starts Testcontainers PostgreSQL/Redis plus the backend process from the sibling `trasck` repo, then checks production startup validation, Redis-backed rate-limit readiness, Swagger/OpenAPI protection, secure auth cookies, and system-admin list/grant/revoke gates. The checked-in GitHub Actions workflow runs this `managed-stack` group on pull requests and `main` pushes when Docker is available and the required `TRASCK_BACKEND_REPOSITORY` repository variable points at the backend repository.
- Frontend browser smoke tests for the route shell, core navigation, Workspace Settings member/invitation workflow, program/portfolio management, automation async worker controls, auth/token/system-admin controls, import review/export worker controls, agent provider/profile/repository/task/dispatch-attempt controls, public-read settings, and public project/work-item/comment/attachment preview route.
- Real-backend browser workflow coverage for UI login, no browser access-token local storage, project work item load/create, saved-filter execution, dashboard render, team creation, custom-field creation, planning/board/release/roadmap UI creation, board detail loading, agent provider/profile/repository/task/dispatch-attempt flows including explicit provider/profile deactivation, work item collaboration records created through the Work route UI, and remaining implemented import, automation, notification, worker, configuration, dashboard, program, admin, token, and public preview workflow families.
- Disposable first-run frontend rehearsal coverage that creates its own setup context on an empty stack, signs in through the UI, loads API-seeded work, touches the core work/planning/configuration/import/automation/agent routes, and includes a longer planning/import/automation/agent creation scenario with screenshots and traces.

## Prerequisites

- Java 21
- Maven
- Docker for managed production-like stack tests and any other Testcontainers-backed coverage
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
- `TRASCK_E2E_MANAGED_PROD_STACK`: set to `true` to let the suite start a separate production-like backend with Testcontainers PostgreSQL/Redis for managed production security coverage. Defaults to `false`.
- `TRASCK_E2E_BACKEND_PROJECT_DIR`: path to the backend repo used by managed production stack tests. Defaults to `../trasck`.
- `TRASCK_E2E_MANAGED_PROD_STACK_TIMEOUT_MS`: startup/failure timeout for managed production stack tests. Defaults to `180000`.
- `TRASCK_E2E_LOCAL_RECEIVER_BIND_HOST`: bind host for the local HTTP receiver used by webhook/worker delivery assertions. Defaults to `0.0.0.0`.
- `TRASCK_E2E_LOCAL_RECEIVER_PORT`: fixed receiver port. Defaults to `6199`.
- `TRASCK_E2E_LOCAL_RECEIVER_PUBLIC_BASE_URL`: URL the backend should call for local receiver assertions. Defaults to `http://localhost:6199`; use `http://host.docker.internal:6199` when the backend runs in Docker and needs to call a receiver on the host.

The local receiver tests require the backend outbound URL policy to allow the configured receiver host before the backend starts. For normal host-based local runs, set `TRASCK_OUTBOUND_URL_ALLOWED_HOSTS=localhost:6199,127.0.0.1:6199` in the backend environment. For Docker-backed runs, allow the host name used by `TRASCK_E2E_LOCAL_RECEIVER_PUBLIC_BASE_URL`.

Do not commit `.env`; use `.env.example` for safe defaults only.

The authenticated smoke tests are skipped unless both login variables are set or `TRASCK_E2E_ALLOW_SETUP=true` can create a disposable first-run stack. Workspace/project API tests also require `TRASCK_E2E_WORKSPACE_ID` and `TRASCK_E2E_PROJECT_ID` unless setup bootstrap creates those IDs in the current run. Use a disposable local test user with the least permission needed for the scenario being exercised; current broad API coverage expects project/workspace admin permissions so it can create and clean up its own test resources through public APIs where cleanup APIs exist.

## Local Stack

Run the backend and frontend with their normal local settings, keeping the backend on port `6100` and the frontend on port `8080`. The smoke suite assumes the backend owns PostgreSQL, Redis, Maildev, and any other service dependencies; `trasck-test` only drives HTTP and browser behavior from outside the application.

For deterministic local runs, start the stack from a known database state, create or reuse a disposable test user, set the optional login and workspace/project variables in `.env`, then run `mvn test` from this repository. For a brand-new disposable database, set `TRASCK_E2E_ALLOW_SETUP=true` instead; the suite will create a Playwright admin/workspace/project through `/api/v1/setup`, reuse those IDs in-memory for the same Maven run, and verify that a second setup call returns conflict. Setup bootstrap intentionally remains minimal. Broader browser workflow data can be created through the separate optional sample-data fixture when `TRASCK_E2E_SEED_SAMPLE_DATA=true`; dedicated real-backend browser tests may also create the minimum seed records they need through authenticated APIs and clean them up through public APIs. The API tests create uniquely named temporary resources and delete the resources they create through Trasck HTTP APIs where public cleanup APIs exist; they do not use direct database access.

Managed production-like stack tests are separate from the normal local-stack assumptions. When `TRASCK_E2E_MANAGED_PROD_STACK=true`, those tests start disposable PostgreSQL and Redis containers, launch the backend from `TRASCK_E2E_BACKEND_PROJECT_DIR` with the `prod` Spring profile and hardened environment values, and write backend process logs under `test-results/runtime`. They are intentionally opt-in because they compile and run the backend as a child process.

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

Run only the managed production-like stack security tests:

```bash
TRASCK_E2E_MANAGED_PROD_STACK=true mvn test -Dgroups=managed-stack
```

## CI

The `.github/workflows/playwright.yml` workflow keeps this repo Java-based. It runs `mvn -q -DskipTests test-compile` as a light pull-request gate, then checks out the backend repo and runs:

```bash
TRASCK_E2E_MANAGED_PROD_STACK=true mvn -q test -Dgroups=managed-stack
```

The workflow requires the repository variable `TRASCK_BACKEND_REPOSITORY`, for example `your-org/trasck`. The managed-stack job fails early when this variable is missing so CI does not silently test against the wrong backend repository.

Artifacts are written under `test-results/`:

- `test-results/api`: response snippets with sensitive headers and token-shaped body fields redacted.
- `test-results/api/backend-route-inventory.tsv`: generated OpenAPI route inventory with `covered`, `planned-high-risk`, and `planned` statuses.
- `test-results/runtime`: managed backend process logs for opt-in production-like stack tests.
- `src/test/resources/backend-route-coverage.tsv`: committed coverage baseline. The OpenAPI route inventory test fails when a backend route is added without an explicit baseline status, and covered rows must name their owning Java Playwright test in `coverageOwner`. The current baseline tracks 399 covered backend routes, 0 ordinary planned routes, and 0 planned high-risk routes.
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
