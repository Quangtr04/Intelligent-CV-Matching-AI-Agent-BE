# Consultant advice reports and private notes

The assigned Career Consultant can save structured advice alongside an approved or completed consultation. This does not create a chat message. The existing candidate CV, career profile and analysis access, CV AI evaluation and job-fit evaluation endpoints remain available under their existing ownership checks.

## Advice API

`GET /api/v1/consultations/{id}/advice` supports the assigned consultant and the owning candidate. The consultant can read drafts. The candidate receives a report only after publication. A missing report or a draft returns `{consultationId, candidateId, status: "EMPTY", version: 0, advice: null}` to the candidate, without draft content or draft version.

`PUT /api/v1/consultations/{id}/advice` accepts:

```json
{
  "expectedVersion": 0,
  "status": "PUBLISHED",
  "resumeId": null,
  "jobId": null,
  "advice": {
    "cvAdvice": { "summary": "Quantify the results of recent projects." },
    "careerAdvice": { "recommendedPositioning": "Target junior backend developer roles." },
    "applicationAdvice": {
      "summary": "Explain how one relevant project meets the job requirements.",
      "coverLetterTips": [],
      "emailTips": [],
      "applicationChecklist": []
    }
  }
}
```

`status` is `DRAFT` or `PUBLISHED`. `cvAdvice` and `careerAdvice` use the existing advice builder's structured fields. `salaryAdvice` and `interviewAdvice` are optional. Publication requires a CV summary, career positioning and job application summary. Strings, list lengths, scores, salary ranges, fields and total content size are validated. Client author and update time values are discarded; the response supplies the database author and time.

The response includes camelCase `consultationId`, `candidateId`, `consultantId`, `resumeId`, `jobId`, `status`, `version`, `advice`, `createdAt`, `updatedAt`, `publishedAt`. Initial writes use `expectedVersion: 0`; each successful write increments `version`. Stale writes return 409. A published report cannot revert to a draft; the consultant can publish an updated version. Each publication creates a candidate notification with type `CONSULTATION_ADVICE_PUBLISHED` and consultation reference.

An attached CV must be `READY` and belong to this consultation's candidate. An attached job must be active and unexpired. No report endpoint grants access to an unrelated candidate or consultant.

## Private notes API

Only the currently assigned consultant can `GET` or `PUT /api/v1/consultations/{id}/private-notes`. Notes are stored independently from the advice report and are never added to candidate responses or notifications.

```json
{
  "expectedVersion": 0,
  "notes": "Private preparation notes",
  "rubric": {
    "cvQuality": 7,
    "technicalDepth": 8,
    "communication": 6,
    "marketReadiness": 7
  }
}
```

The rubric is optional. If supplied, all four scores must be integers from 0 to 10. Notes allow up to 10,000 characters. The response contains `consultationId`, `consultantId`, `notes`, `rubric`, `version`, `updatedAt`. Missing notes start at version 0. Audit logs record the action, consultation and version without the private text or rubric.

## Lifecycle and persistence

Reports and notes may be written for session bookings in `ACCEPTED` or `COMPLETED` state that have not been refunded. This allows post-session advice after Google Meet ends. Pending, declined and cancelled bookings cannot receive new advice or note updates. Legacy consultations must still be `OPEN` to accept writes. Published reports remain readable after completion or closure.

Migration `V32__consultant_structured_advice.sql` adds `consultation_advice_reports` (one report per consultation) and `consultation_private_notes` (one private record per consultation and consultant). Writes lock the consultation row and enforce expected versions before atomic updates; simultaneous consultation transitions fail promptly with 409 rather than silently overwriting work.

## Verification

`ConsultationAdviceServiceTests` validates draft visibility, publication, references, lifecycle restrictions, private notes, stale writes and bounded content without a database.

`ConsultationAdviceDatabaseTests` runs against an explicitly selected disposable localhost PostgreSQL database ending in `_test` via `-Dconsultation.testDatabaseUrl=jdbc:postgresql://127.0.0.1:55442/roles_test`. It exercises actual membership filters, persistence, notifications, optimistic SQL writes, foreign CV rejection and private note isolation. Fixtures roll back after each scenario and do not call Google or AI providers.
