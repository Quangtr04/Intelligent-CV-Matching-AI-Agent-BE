# CV AI request diagnostics

The CV/job fit page validates selections against the signed-in candidate's current CV list and
the current job catalog. Saved jobs retain status and expiry metadata; an explicit job outside
the first catalog page is validated with its detail endpoint. Unavailable links require a new
selection rather than silently switching to another CV or job.

Account changes clear cached queries. Refresh responses and retried requests from a previous
login cannot overwrite or reuse the new login. The resume analysis page no longer uses the
placeholder ID `cv-1` while loading.

ATS validates the CV owner/status and active job before invoking AI or reserving quota. A 404
explains which selection is unavailable. The frontend refreshes selectors after a 404 and does
not automatically retry the paid AI operation.

For reviewed CVs, AI quality-analysis inputs include the authoritative structured content and
omit historical raw extraction. Evidence validation remains strict: quotations must match the
reviewed content. Raw-only CV inputs preserve their extraction text.

Provider errors are separate from application entitlements:

| Failure | Application HTTP response |
| --- | --- |
| Candidate application token budget exhausted | 429, application quota message |
| Gemini request/token limit (upstream 429) | 503, explicit provider-limit message |
| Provider temporarily unavailable (upstream 503) | 503, explicit provider-unavailable message |
| Provider internal error (upstream 5xx) | 502, explicit provider error and HTTP status |

Provider responses are not automatically retried. When generation fails before a response is
received, the application quota reservation is cancelled. Logs record the provider HTTP status
without provider response bodies or credentials.

Verification includes frontend selection/account-switch tests, ATS preflight tests, provider
error classification tests and reviewed-content evidence tests. Live diagnostics on 6 November
2026 succeeded for candidate chat, CV support, embeddings and ATS with synthetic owned data.
CV quality analysis encountered upstream internal errors; a recorded successful raw-provider
reply also exposed stale raw-text evidence, addressed by the authoritative-input change.
Successful end-to-end quality analysis after that change still requires a new live verification
when provider quota is available. Do not consume provider quota through automatic retry loops.
