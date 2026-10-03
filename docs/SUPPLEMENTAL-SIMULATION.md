# Simulation draft and notification supplement — 2026-10-03

This is a supplemental implementation contract. The original frozen OpenAPI remains 76 operations and unchanged. These routes run through the original Yudao SimulationApiFilter / SimulationEngine; no independent server was introduced. All examples use synthetic participants and in-process delivery, never external messages, WeChat, payment rails, or real-funds activity.

## Common boundary

Base path `/api/v1`; JSON response `{data,meta:{requestId,serverTime,environment:"SIMULATION"}}`. Bearer sessions are required. Mutations need a 16–128-character `Idempotency-Key`. Unknown body fields, invalid types, empty/whitespace-only values, undeclared query fields, and stale versions are rejected. The original per-user mutation/read minute rate limits apply. Idempotency compares a semantic path/parameters/body hash and the caller's active grants before replay. Applicant ownership and reviewer/controller resource scope are server checked; matching a role alone does not widen scope.

## Application routes

| Method | Path | Body | Access |
| --- | --- | --- | --- |
| POST | `/merchant-application-drafts` | Any subset of `merchantName`, `regionCode`, `contractVersion`, `materialSummary` | Active applicant; at most one DRAFT/SUBMITTED/NEEDS_INFO application |
| GET | `/merchant-application-drafts/{id}` | none | Applicant only |
| PATCH | `/merchant-application-drafts/{id}` | `version`, at least one material field | Applicant, DRAFT or NEEDS_INFO |
| POST | `/merchant-application-drafts/{id}/submit` | `version` | Applicant, completed materials, DRAFT or NEEDS_INFO |
| GET | `/merchant-applications/{id}/history` | none | Applicant or merchant-scoped reviewer; unsubmitted draft private |
| POST | `/admin/merchant-applications/{id}/request-information` | `version`, `reason` | Independent merchant-scoped MERCHANT_REVIEWER, SUBMITTED only |

Material field length limits are 128/16/40/1000 respectively. Partial drafts can be saved; all four fields are required at submission. First submission has materialVersion 1, resubmission increments it and requires a changed material hash. NEEDS_INFO preserves the review reason while editing; SUBMITTED locks materials. APPROVED and REJECTED are terminal for that application. Frozen decision routes continue to approve/reject submitted applications.

Supplemental application detail fields: `id, merchantId, merchantUid, applicantId, status, materialVersion, submittedMaterialVersion, reviewReason, version, materials, materialHistory, history, simulationOnly`. Owners see current working materials. Reviewers see only the last submitted material snapshot when a private edit exists. `applicationMaterials` stores immutable submitted material versions and SHA-256 hashes. `applicationHistory` stores append-only save/submit/review events and actor/request IDs. Frozen application responses keep their original shape. These are simulation participation records, not real identity certification or formal approval.

## Notification routes

| Method | Path | Body | Access |
| --- | --- | --- | --- |
| GET | `/notification-outbox` | none | Recipient's rows; global SIM_CONTROLLER sees all |
| GET | `/notification-outbox/{id}/attempts` | none | Same recipient/controller visibility |
| GET | `/notification-outbox/{id}/retries` | none | Same recipient/controller visibility |
| GET | `/simulation-controls/notification-failures` | none | Global SIM_CONTROLLER |
| POST | `/simulation-controls/notification-failures` | `version, remainingFailures, reason`, optional `userId` | Global SIM_CONTROLLER, fixtureMode only |
| POST | `/notifications/{id}/retry` | `version, reason` | Global SIM_CONTROLLER, fixtureMode only; `id` is the outbox ID |

Outbox DTO: `id,userId,title,body,resourceType,resourceId,eventVersion,template,status,attemptCount,cycle,cycleAttemptCount,nextAttemptAt,lastAttemptAt,lastError,deliveredAt,deadAt,notificationId,createdAt,updatedAt,version,environment`; null optional fields may be absent. List endpoints return an array in `data`.

Failure config ID is `notification-delivery`; before configuration its version is 1 and remainingFailures is 0. Configuration and every consumed failure bump that version. Read it before each edit. remainingFailures must be 0–100; optional userId targets one synthetic recipient.

The business transaction atomically records `notificationOutbox`; delivery happens in a subsequent sweep. Dedupe is `(resourceType,resourceId,eventVersion,userId,template)`. A sweep attempts each due row once. PENDING becomes DELIVERED or RETRY_WAIT. Failed attempts schedule +30 seconds then +60 seconds; third failure becomes DEAD with no next attempt. Controller retry is permitted only from DEAD and opens a new bounded three-attempt cycle. Lifetime attemptCount stays monotonic. `notificationAttempts` and `notificationRetryAudits` are append-only; retry never overwrites past attempts. Success creates exactly one existing notification row; the frozen `/notifications` API and idempotent read semantics stay unchanged. Unknown/inactive recipients fail locally; there is no outbound transport.

## Verification and limits

Tests cover partial drafts, immutable versioning, scoped access, same-person rejection, private working edits, stale versions, canonical replay/conflicts, active-grant replay denial, input rejection, Bearer boundary, rate limits, dedupe, 30/60-second retry timing, dead letters, restart serialization, bounded retry cycles, append-only history, and fulfillment/accounting surviving delivery failure. The test clock is controlled, and verification starts no database or GUI.

Persistence remains the existing five MySQL simulation JSON/projection tables. The new record collections are stored in that partition and protected by the JdbcSimStore append-only guard; this is not a canonical 38-table migration, production database privilege enforcement, independent acceptance sign-off, or a public production release. A local source/test milestone does not mean the running service has been rebuilt or restarted.
