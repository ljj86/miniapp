# Shop Java implementation and verification

## What changed

This iteration adds a separately registered support contract to the original Java simulation engine. The original frozen 76-operation contract remains unchanged. The source is based on `77bd867b5884779a00f5fe2d6e4d74143db424b7`; this source iteration adds loopback HTTP integration, not a new tag, GitHub Release object, hosted deployment, or external provider connection.

| Verified newer Shop source feature | Java implementation | Storage | Verification |
|---|---|---|---|
| Shop-bound customer service, staff reply, unread | SupportCatalog + SupportModule, `/api/v1/support/*`; authenticated actor and current shop grant derive authority; explicit last-seen read cursor | supportSessions, immutable supportMessages | domain scope/replay/unread tests; actual loopback HTTP customer→merchant flow |
| Feedback levels, multi-reply workflow, archive/restore | HIGH/MEDIUM/LOW, 200-character title; versioned state machine; owner/platform archive, merchant no archive | supportTickets, immutable supportTicketReplies and supportTicketHistory | state conflicts, reopen, owner archive, revoked merchant access including idempotent replay |
| Images/files/video attachments | bounded Base64 upload, canonical bytes/type/name validation, owned IDs, current-scope authenticated downloads | supportAttachments in existing aggregate; 512KiB per file, 5 files/1MiB per command | all accepted MIME types, invalid data/path, quotas, cross-user/shop/archive scope |
| Manuals/material center/update history | built-in plain-text help, versioned custom materials and immutable history; controlled reviewed PDF/PNG assets when packaged | supportResources, resourceHistory; built-ins in classpath | resource role/version/history tests; manifest integrity and manual asset tests |
| AI configuration status | global SUPPORT metadata only; enabled/model/welcome text | supportPlatformConfig, platformConfigHistory | non-admin denied, stale version rejected, never connected, no network/secret fields |
| Payment channel management | MOCK / ALIPAY / WECHAT / BANKCARD display metadata | supportPlatformConfig, platformConfigHistory | only MOCK ready; real channels stay not connected and charges disabled |
| Personal display profile | authenticated actor's own nickname/contact fields, separate from credential/finance identity; controlled avatar asset | supportProfiles | own-only read/write, stale version, spoofed identity and external avatar blocked |

The source deployment bundle had no editable Java/Vue source and was statically inspected, never executed. This implementation is added to the existing maintained Java source rather than shipping a modified untrusted JAR. No original exported user/credential/payment records were copied.

## Persistence and migration boundary

Production wiring of this simulation remains `SimulationConfiguration → JdbcSimStore → sim_v1_partition`. The existing single-row transaction lock serializes aggregate changes and prevents partial attachment/ticket mutations. New record kinds are lazily materialized inside the existing JSON aggregate; there is **no new normalized support-table migration** to execute and no claim of production database design. Old partitions with no support records remain readable. All session/ticket/config/attachment/profile data flows through the same store transaction. Support messages, replies and histories were added to the existing append-only evidence guard; generic audit rows remain append-only and projected by the JDBC store.

This architecture has deliberately limited scale and no object storage or malware scanner. Attachment bytes are not public URLs; no file-system request path or remote fetch is accepted. MIME/signature checks are not virus scanning. Attachment DTOs say `UNSCANNED_SIMULATION`. Real credentials, personal records or money must not be put into the fixture. A production migration needs separately designed relational tables/indexes, object storage, scanning, retention, transactional idempotency policy and verified production identity mapping.

AI configuration cannot establish an external client or save API secrets. No network client is present. Real payment metadata cannot charge, process gateway callbacks or make a provider ready. Original credit/order/repayment/refund invariants and simulation-only guards remain in force.

## Verification commands

From the repository root:

```
mvn -f verification/pom-with-guard.xml test
```

The original baseline had 64 focused tests. The latest aggregate run passed 95 tests (0 failures/errors/skips), including 31 new tests covering support domain, profile, resources, attachments, controlled manuals, and real servlet HTTP with service restart. The original commerce/finance suite still ran 13 scenarios with 242 assertions. These counts do not imply MySQL or complete upstream reactor coverage. Ticket writes and lists use compact DTOs; full history remains available by explicit detail read, avoiding quadratic idempotency snapshot growth.

For an explicit local HTTP integration session, first run the tests, then:

```
python3 verification/run-support-http-fixture.py --port 18081
```

The fixture binds only `127.0.0.1:18081`. It uses the actual `SimulationApiFilter` and `SimulationEngine` but an explicitly test-only atomic JSON file store, not MySQL and not the full upstream Spring application. It seeds synthetic approved merchant/store data to exercise the new UI against authenticated API requests. The default private runtime state under `verification/support-http-data/` is gitignored and must never be included in source archives because it contains synthetic session tokens and test records. Stop with Ctrl+C. Reusing the same state path preserves data; intentionally selecting a new path starts fresh synthetic data.

Synthetic accounts use the original `/auth/sms-challenges` and `/auth/sessions` flow. Do not equate the separate browser demo's 111/111 with Java authentication. Test actors include SIM-USER-001, SIM-USER-002, SIM-OWNER-001 and SIM-SUPPORT-001. The fixture descriptor endpoint explains the synthetic OTP. OTP challenges are rate limited to one per identity per minute; the fixture does not bypass that guard. Session tokens are server issued.

## Not verified by these checks

- MySQL/JDBC connectivity, database restart, or production load
- Full original Yudao module reactor and its production dependencies
- Hosted Java backend, TLS/reverse proxy/origin policy, public accessibility or cloud deployment
- Production authentication, external AI, real payment, SMS, account credentials or virus scanning

The front-end adapter must preserve this boundary: explicit backend connection, server IDs from context, no caller actor IDs, no silent localStorage fallback, and no claim of a hosted Java service merely because a static Site is accessible.

## Running a frontend adapter against actual HTTP

If separate executor commands do not share a loopback namespace, run Java and the frontend test in the same command using:

```
python3 verification/with-support-http-fixture.py --cwd apps/dining-mall -- node tests/backend-support-ui.test.mjs
```

The runner starts Java on loopback, waits for actual HTTP health, supplies `SHOP_API_BASE` to the child command, and stops Java afterward. It uses a fresh temporary synthetic state for each run; no runtime tokens are written into source. The specific frontend test file must exist in the integrated frontend deliverable. Successful runner health alone is not a frontend test pass.

## Actual front-end transport integration result

The actual `src/utils/service-http.js` from the new dining UI was imported by `verification/shop-ui-http-smoke.mjs` and called the real Java servlet over loopback, without a mock fetch or localStorage substitution. The checks passed for authenticated role/context, customer message+attachment→merchant read/reply, unread cursors, cross-user rejection, message idempotency, compact ticket/detail workflow and archive/restore, server search/pagination, genuine PDF/page download hashes, platform AI/BANKCARD metadata boundaries, custom material persistence, and private display profile.

Reproduce after integrating the new dining UI:

```
python3 verification/with-support-http-fixture.py -- node verification/shop-ui-http-smoke.mjs
```

The script optionally takes an explicit transport-module path. This proves the new browser transport and Java API contract against actual HTTP; it is not a pixel/UI browser acceptance test, a hosted-backend test or a MySQL integration test. The Java HTTP integration test separately verifies data and authenticated sessions survive a servlet/store reconstruction using the atomic JSON test store.
