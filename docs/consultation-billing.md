# Launch plans and paid consultation

> Updated workflow: V27 replaces new per-request purchases with prepaid session packs and
> candidate-selected appointments. See [consultation-booking.md](consultation-booking.md).
> The per-request fulfillment described below applies only to historical non-pack orders.

Flyway migration V26 installs the consultation catalog and updates the three existing launch plans.
Run the backend normally with Flyway enabled to apply it to the application's configured database.
Historical migrations are unchanged. Local PostgreSQL smoke tests use a separate database.

| Plan | Price | Shared AI budget |
| --- | ---: | ---: |
| Guest trial | Free | 10,000 tokens per new guest session |
| Free | Free | 30,000 tokens per calendar month |
| Basic | 29,000 VND / 30 days | 250,000 tokens per 30-day cycle |
| Pro | 69,000 VND / 30 days | 800,000 tokens per 30-day cycle |

Guest sessions retain their existing 24-hour expiry and creation throttling.
GUEST_TOKEN_LIMIT overrides the guest default; set it to 10000 in deployed environments.
Existing guest sessions retain their originally issued budgets.
Paid subscription duration is 30 days times durationMonths. A 12-cycle purchase lasts 360 days.
Free quota uses MONTHLY; paid quota uses SUBSCRIPTION, anchored to activation and reset every 30 days.
Enabled features share AI_TOTAL_TOKENS; feature quota zero continues to block that feature.
Unused quota does not roll over. Top-ups are not offered in this release.
Gemini countTokens now supplies the input reservation; bounded output plus a small safety margin is
reserved before generation. Provider usage settles the reservation after the request.

| Consultation service | Unit | Price |
| --- | --- | ---: |
| CV_REVIEW_ASYNC | One CV review | 49,000 VND |
| CAREER_ROADMAP_ASYNC | One career roadmap request | 79,000 VND |
| CAREER_CALL_30 | One 30-minute session | 99,000 VND |
| CAREER_CALL_60 | One 60-minute session | 179,000 VND |

## Management

Admin and Management share the existing ADMIN authorization policy.
The web screen is /admin/consultation-services, accessible from the management navigation.
Admins can add services, change prices/details, and deactivate sales; records are not physically deleted.
Live services require durationMinutes from 1 to 480; async services have no duration.
Only positive whole-number VND prices are accepted.

| Method | API under /api/v1 | Access |
| --- | --- | --- |
| GET | /consultation-services | Public, active catalog only |
| GET | /admin/consultation-services | Admin/Management, including inactive services |
| POST | /admin/consultation-services | Admin/Management, create |
| PUT | /admin/consultation-services/{id} | Admin/Management, update |
| POST | /consultation-orders | Candidate, create pending order |
| GET | /consultation-orders | Candidate's own orders; Admin/Management sees all |
| POST | /consultation-orders/{id}/cancel | Owner, unpaid orders only |
| POST | /payments/orders/{paymentId}/sepay-checkout | Existing owner-only SePay checkout |

Service write payload:

```json
{
  "serviceCode": "CV_REVIEW_ASYNC",
  "displayName": "Review CV bất đồng bộ",
  "description": "Một lần chuyên gia đánh giá và góp ý CV.",
  "deliveryMode": "ASYNC",
  "durationMinutes": null,
  "price": 49000,
  "isActive": true
}
```

Consultation order payload:

```json
{
  "serviceId": "<catalog UUID>",
  "subject": "Review CV Backend",
  "description": "Nội dung yêu cầu và thông tin CV cần đánh giá."
}
```

The server reads the active service price; clients never supply the payable amount.
The order snapshots service code/name, delivery mode, duration, amount and currency.
Later catalog edits cannot change pending or historical order prices.
SePay must be configured before accepting new paid consultation orders.
An unpaid order can be resumed from the candidate's consultation page.

## Payment and fulfillment

1. Candidate selects a service and provides the consultation details.
2. Backend atomically inserts consultation_orders and a linked CONSULTATION payment_orders row.
3. The existing SePay checkout signs the stored payment amount and returns provider form fields.
4. Authenticated IPN verifies provider, event ID, order ID, amount and currency.
5. Successful settlement marks the order PAID, creates exactly one consultation thread and invoice,
   and notifies the existing Admin/Management assignment queue.
6. Existing consultant assignment, messaging and completion flows fulfill the service.
   Closing the thread also marks the corresponding order COMPLETED.

Payment failure/cancellation cancels the unpaid consultation order. A cancelled payment cannot later
be activated by a webhook. Duplicate webhook delivery cannot create another thread or invoice.
Browser redirects only display payment status; they do not activate orders or subscriptions.
Consultation settlement never activates, replaces or cancels an AI subscription.
Existing DIRECT_CHAT is retained for initial contact; formal service requests use the paid order flow.
Paid refunds, consultant payout accounting, automatic appointment scheduling and video calls are
separate workflows; this catalog does not implement them.

## Verification

Unit tests cover server-side price snapshots, role checks, inactive services, payment replay and failure.
ConsultationBillingDatabaseTests additionally verifies V26 seeds, checkout-to-thread settlement,
price changes, ownership, cancellation and the 30-day quota boundary on PostgreSQL.
It requires an explicitly supplied consultation.testDatabaseUrl pointing to a disposable localhost
database whose name ends in _test; every scenario rolls back.
