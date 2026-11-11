# Manager role

`MANAGER` is a separate persisted user role. It does not inherit `ADMIN`.
Admin creates or updates Manager accounts through the existing users API.
Self-registration still creates Candidate accounts.

The Manager frontend starts at `/manager`. Its modules include dashboard, subscription packs,
subscribers, consultation pricing, payment/invoice history, consultant quality and booking monitoring,
job collection, announcements, feedback, support and analytics.
Personal account and notifications are also available.

The backend grants these modules through a request allowlist and `requireOperationsRole()`
inside the corresponding services. `requireRole("ADMIN")` still rejects Manager, including
account administration, role changes, AI configurations/prompts and system/audit logs.
Job workers also validate the persisted active role when processing Manager-requested work.

## Operational reports

The read-only reporting API is under `/api/v1/manager` and accepts strict `ADMIN` or `MANAGER`.
The supported account roles are `CANDIDATE`, `CAREER_CONSULTANT`, `MANAGER` and `ADMIN`.

| GET path | Result |
| --- | --- |
| `/dashboard` | Existing account/subscription/revenue/AI/support metrics plus `service_metrics` for bookings and ratings |
| `/analytics` | `usage_by_day`, `revenue_by_day` separated by currency, and a consultation quality summary |
| `/subscriptions`, `/subscriptions/{id}` | Subscriber status and plan, with plan quotas in detail |
| `/usage` | Per-user/per-feature request count, tokens, estimated cost, success/failure and average latency |
| `/payments`, `/payments/{id}` | Payment order status and amounts, with the applicable invoice in detail |
| `/invoices`, `/invoices/{id}` | Invoice amounts, currency, status and issue/payment dates |
| `/consultants` | Consultant activity, booking counts and average ratings |
| `/consultants/{id}/bookings` | Booking state/times, meeting state and Candidate rating/comment |
| `/crawl-reports` | Source collection state and operational counters |
| `/feedback` | Matching, assistant and consultation feedback |

List endpoints support `limit` (1–100) and `offset` (0–100000). Subscriber/payment/invoice lists
also accept `status` and `search`; consultants accept `search`; consultant bookings accept `status`;
crawl reports accept `source` (`ITVIEC`/`TOPCV`) and `status`.
Analytics and usage accept inclusive ISO dates `from` and `to`, interpreted in `Asia/Ho_Chi_Minh`,
with a default 30-day window and a maximum 366-day window. Usage also accepts `feature`.
Report queries deliberately omit credentials, payment provider payloads, raw CV data, private consultant notes
and meeting join URLs. Reporting endpoints do not mutate payment, invoice or booking statuses.

## Delegated token and AI API quotas

`GET /api/v1/manager/quota-limits` returns the current account's `maxTokenQuota`, `maxApiQuota`,
`allowedFeatureCodes`, `allowedPeriods` and `source` (`DEFAULT_POLICY` or `ADMIN_OVERRIDE`).
Defaults are 10,000,000 tokens and 100,000 AI calls per quota rule. Server environment variables
`MANAGER_MAX_TOKEN_QUOTA` and `MANAGER_MAX_API_QUOTA` configure the defaults; Manager cannot change them.
Only strict Admin can read/change another Manager's ceilings through
`GET/PUT /api/v1/admin/managers/{id}/quota-limits`, with body
`{"maxTokenQuota":10000000,"maxApiQuota":100000}`. Updates are audited and require a Manager target.

Manager reads even inactive plan rules through `GET /api/v1/admin/subscription-tiers/{id}/quotas` and
uses the existing PUT endpoint to upsert rules. Omitting a rule preserves its current value.
Manager may use only `MONTHLY` or `SUBSCRIPTION`, nonnegative values and supported feature codes,
and every submitted value must fit the delegated ceiling. Backend validation runs before any quota write.
Admin retains the broader existing periods. Rules already outside a Manager's authority require Admin
to adjust or explicit reduction to an allowed value before that Manager can save them.

`AI_TOTAL_TOKENS` and the twelve AI feature codes retain their token-budget meaning.
`API_CALLS_TOTAL` and `API_CALLS_<feature>` additionally limit successful or in-flight AI reservations;
cancelled reservations do not consume a call. These rules do not represent arbitrary HTTP requests or RPM.
Absent call rules preserve existing behavior. Counting and reservation share the existing user row lock,
so concurrent calls cannot spend the same final allowance. Existing token accounting remains separate
from provider usage and cost telemetry.

V33 adds `manager_quota_limits`. AI provider/model/key configuration, security/account administration,
and RAG indexing/administration require strict Admin. Manager retains read-only vector/indexing metrics.

## Job operations

Only `ADMIN` and `MANAGER` can import jobs, request crawls, configure collection schedules,
review job status or index collected jobs. Creator and reviewer attribution remains available.
Job and crawl requests no longer accept an internal task assignment.

Migration V34 removes the obsolete internal task module and restricts persisted account roles
to the four supported roles. Historical Flyway migration files retain their original checksums.

Security verification includes real HTTP requests and PostgreSQL migrations in
`SecurityHttpIntegrationTests`, service authorization in `ManagerRoleAuthorizationTests`,
and frontend route/login policy checks in `tests/manager-access.test.mjs`.
`ManagerOperationsServiceTests` covers service boundaries and reporting validation.
Job tests verify operations-role authorization before imports or external crawling.
`ManagerOperationsDatabaseTests` runs the SQL projections and monetary aggregates against
a disposable localhost PostgreSQL database when `-Droles.testDatabaseUrl` is provided; each fixture rolls back.
