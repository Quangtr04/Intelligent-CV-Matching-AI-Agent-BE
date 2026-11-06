# Manager role

`MANAGER` is a separate persisted user role. It does not inherit `ADMIN` or the legacy
`MANAGEMENT` role. Admin creates or updates Manager accounts through the existing users API.
Self-registration still creates Candidate accounts.

The Manager frontend starts at `/manager`. Its modules are dashboard, subscription packs,
consultation pricing, payment history/reports, CV templates, job collection, documents,
announcements, feedback, support and analytics. Personal account and inbox are also available.
The feedback view combines the latest 100 matching, assistant and consultation feedback records.

The backend grants these modules through a request allowlist and `requireOperationsRole()`
inside the corresponding services. `requireRole("ADMIN")` still rejects Manager, including
account administration, role changes, AI configurations/prompts and system/audit logs.
Job workers also validate the persisted active role when processing Manager-requested work.

Migration V29 only extends the existing role constraints on `users` and
`announcement_targets`. It does not create tables, promote accounts, or modify existing roles.

Security verification includes real HTTP requests and PostgreSQL migrations in
`SecurityHttpIntegrationTests`, service authorization in `ManagerRoleAuthorizationTests`,
and frontend route/login policy checks in `tests/manager-access.test.mjs`.
