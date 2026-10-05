# Shop support resources, attachments and platform metadata

This contract supplements `SHOP-SUPPORT-CONTRACT.md`. It is a source implementation for the existing guarded Java simulation, not a deployment, production-authentication, payment or AI integration claim. Base path is `/api/v1`; use the existing authenticated simulation bearer session. Successful JSON is `{data, meta:{requestId,serverTime,environment:"SIMULATION"}}`. Mutation requests need a 16–128-character `Idempotency-Key`. The server rejects unknown fields/query parameters, invalid types, stale versions, inactive users and out-of-scope actions.

## Resources and manuals

| Method | Path | Body/query | Result/access |
| --- | --- | --- | --- |
| GET | `/support/resources` | Optional `kind,q,audience` | Active resource array; authenticated active user |
| GET | `/support/resource` | Required `id` | Resource DTO; archived entries only global SUPPORT |
| GET | `/support/manuals` | Optional `q,audience` | Active MANUAL array; authenticated active user |
| GET | `/support/manual` | Required `id` | MANUAL DTO; same access |
| GET | `/support/updates` | Optional `q,audience` | Active UPDATE_LOG array; authenticated active user |
| GET | `/support/admin/resources` | Optional `kind,q,audience,status` | All resources, including archived; global SUPPORT |
| POST | `/support/resources/create` | `kind,title,content,audience` | New ACTIVE resource; global SUPPORT |
| POST | `/support/resources/update?id=…` | `version,kind,title,content,audience` | Full material replacement of ACTIVE custom resource; global SUPPORT |
| POST | `/support/resources/archive?id=…` | `version` | Archived resource; global SUPPORT |
| GET | `/support/resources/history?id=…` | Required `id` | Append-only history array; global SUPPORT |

Resource DTO: `id,kind,title,content,audience,status,version,createdAt,updatedAt,readOnly,environment,simulationOnly,contentFormat`; optional `archivedAt,source`. IDs are numeric strings. `kind` is `KNOWLEDGE|MANUAL|UPDATE_LOG`; `audience` is `CUSTOMER|MERCHANT|ALL`; `status` is `ACTIVE|ARCHIVED`. `audience` is a help-content category, not an identity or security permission. All active authenticated users can read all active published help. Audience filters also include `ALL` content.

Limits: title 1–160 characters, content 1–16000, search 1–100, up to 500 persisted custom resources including archived rows. Lists return arrays; paginate locally if needed. Material fields are required on update. Updating unchanged materials returns `MATERIAL_UNCHANGED`; archived resources cannot be edited. Versions are integers from 1 through 100000000. Six built-in entries have IDs `1` through `6`, `readOnly:true`, and fresh deterministic DTOs. Reading does not create persisted resource/configuration records. Built-in content is source-backed operational guidance; entry `6` is explicitly `SOURCE_IMPLEMENTATION`, not a claimed release/deployment. Create a custom entry to add guidance instead of overwriting built-ins.

History entries have `id,resourceId,resourceVersion,action,actorId,requestId,snapshot,snapshotHash,createdAt,updatedAt,version,environment`. `action` is `CREATED|UPDATED|ARCHIVED`; `snapshot` is the complete resource DTO for that version and `snapshotHash` is its canonical SHA-256. History is added in the same business transaction and protected by the aggregate append-only guard. Built-ins have no mutation history. No hard-delete operation exists.

Render all `content`, titles, labels and welcomes as plain text (`contentFormat:"PLAIN_TEXT"`), never HTML. Built-in manuals retain this plain-text content alongside verified PDF and PNG page assets described below. Custom manuals can be downloaded by building a UTF-8 `text/plain` Blob from returned content; they do not acquire a generated PDF implicitly.

## Built-in manual PDF and page images

`GET /support/manual-asset?id=1&format=PDF` downloads a complete built-in manual as JSON inside `data`. PDF requests must omit `page`. `GET /support/manual-asset?id=1&format=PNG&page=1` returns a rendered page image; `page` must be exactly `1` or `2`. Active authenticated users may read these assets. IDs are restricted to the existing built-in manuals `1` customer, `2` merchant and `3` platform. Each is a genuine two-page PDF with two corresponding PNG pages.

Built-in MANUAL DTOs include `availableAssets`, ordered PDF, PNG page 1, PNG page 2. Each descriptor has `manualId,format,name,mime,size,sha256,source:"BUILTIN_MANUAL_ASSET",environment:"SIMULATION"`; PNG descriptors also have integer `page`. The download DTO adds canonical `base64`. This is still the normal authenticated JSON envelope, not a public raw asset path. Descriptors contain no Base64 bytes, URL or internal classpath. Build a Blob after decoding and revoke object URLs when replaced or dismissed. Use PNG page blobs for in-app preview and pagination, with a distinct explicit PDF download action.

The build packages `simulation/manual-assets/manifest.json` and exactly the fixed manual names `customer-manual.pdf`, `customer-manual-page-1.png`, `customer-manual-page-2.png` and the corresponding `merchant-manual`/`platform-manual` files. The Java class maps ID and format/page to these fixed names; request data and manifest paths are never used to select arbitrary filesystem or classpath resources. The manifest has `{version:1,manuals:[{id,title,assets:[{format,file,mime,size,sha256,page?}]}]}`; PDF entries omit page, PNG entries require page 1 or 2. Unknown fields, duplicates, wrong names/formats/pages, missing files, size mismatches, SHA-256 mismatches and invalid signatures fail closed. Every asset is bounded to 8 MiB.

The source bundle is verified before availability is advertised or bytes returned. A build without the bundle gives `availableAssets:[]` and downloads return `MANUAL_ASSET_UNAVAILABLE`, leaving text available. An inconsistent bundle returns `MANUAL_ASSET_INVALID` rather than substituting or mislabeling bytes. These controlled source documents are separate from uploaded customer attachments and do not imply that user-uploaded files have been scanned.

## Attachments

| Method | Path | Body/query | Result |
| --- | --- | --- | --- |
| POST | `/support/attachments/upload` | `{name,mime,base64}` | Attachment metadata DTO |
| GET | `/support/attachments/detail` | Required numeric `id` | Attachment metadata DTO |
| GET | `/support/attachments/download` | Required numeric `id` | Download JSON inside `data` |

Use canonical padded standard Base64 without whitespace, data-URL prefix or URL-safe substitutions. Maximum encoded length is 699052 characters; decoded size must be 1–524288 bytes (512 KiB). Name is a UTF-8 safe basename of 1–128 characters: no slashes, traversal, URL/path metacharacters, control/bidi-format characters, or HTML/SVG/script/executable suffixes. It is a display/download name only and is never used as a filesystem path. Body fields such as `url`, `path`, `dataUrl` and `resourceId` are forbidden on upload.

Allowed MIME values: `image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain,video/mp4`. Binary types require matching signatures/container markers; PNG chunks also have bounded lengths and CRC validation; MP4 requires supported `ftyp` and a media/movie box. Plain text must be valid UTF-8 without binary controls or HTML/SVG/script document markup. Type validation is deliberately bounded and is **not** complete file validation or malware scanning. MIME/name checks cannot establish that a PDF, image or video is safe.

Metadata DTO: `id,name,mime,size,sha256,securityStatus,createdAt,updatedAt,version,environment`, plus `resourceType,resourceId,boundAt` after binding. Metadata never contains `base64`, a storage path, public URL or uploader identity. The status is always `UNSCANNED_SIMULATION`; the hash is SHA-256 of the decoded bytes.

Download JSON contains `id,name,mime,size,sha256,base64,securityStatus,environment`. It is **not** a raw file response. Frontend should explicitly decode Base64 and create a Blob with the returned allowlisted MIME, use a user-triggered download link with the safe returned name, then revoke the object URL. Do not treat the JSON endpoint as a public image URL or automatically inline/open PDF attachments. Warn that files are unscanned before users open them.

Uploads are private to their uploader until associated with an authorized session or ticket command. Binding accepts authoritative attachment IDs only. Each command can associate at most **5 distinct IDs**, with a combined decoded size at most **1 MiB**. Every ID must belong to the current actor and be unbound or already bound to exactly that same resource. All checks occur before binding any item. Resources must exist and be readable by the actor in the same transaction. The support command determines whether the actor may write that resource; attachment readability alone does not grant posting rights. No arbitrary URL, filesystem path, remote fetch, data URL or caller-controlled uploader can become attachment evidence.

After binding, detail/download authorization is recomputed through the current resource scope, including revoked grants and the parent resource's archive rule. An uploader does not bypass that rule. Ticket archive access is limited by `SupportModule` to its owner and global SUPPORT; merchant access does not persist after archival. This prevents attachment endpoints from bypassing parent-resource controls. Binding to another resource is rejected, even for the same uploader.

Lifetime stored-byte quotas are 8 MiB per actor and 32 MiB per simulation aggregate, counting bound and unbound files. Metadata counts are also capped at 128 attachments per uploader and 1024 per aggregate, preventing arbitrarily many tiny files. These are simulation safety bounds, not a production storage plan. Quota exhaustion is explicit (`ATTACHMENT_QUOTA_EXCEEDED`); no silent deletion/cleanup or scan-success claim occurs. Session lifetime attachment volume is governed by these quotas; the 1 MiB limit is per command, not per conversation.

## Non-secret platform configuration

All routes below require an active **global** `SUPPORT` grant, checked with `inScope(null,null,null,"SUPPORT")`. A merchant/store/book-scoped SUPPORT grant is insufficient. Ordinary customer/merchant roles cannot read or change these platform settings.

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| GET | `/support/platform/ai` | None | AI metadata DTO |
| POST | `/support/platform/ai` | `version,enabled,model,welcomeMsg` | Updated AI metadata DTO |
| GET | `/support/platform/payments` | None | Ordered MOCK, ALIPAY, WECHAT, BANKCARD DTO array |
| POST | `/support/platform/payments?id=MOCK\|ALIPAY\|WECHAT\|BANKCARD` | `version,displayEnabled,label` | Updated payment metadata DTO |

AI DTO has `id:"AI",version,enabled,model,welcomeMsg,status:"NOT_CONNECTED",externalCallsEnabled:false,secretConfigured:false,configurationOnly:true,environment:"SIMULATION"` and optional `updatedAt`. Defaults are version 1, enabled false, model `unconfigured`. Model names are 1–80 ASCII letters/numbers/dot/underscore/hyphen/colon, starting with a letter/number, and cannot start with `sk-`; addresses, paths and key fields are unsupported. Welcome text is 1–500 characters. `enabled:true` saves display/configuration intent only; it never changes the connection state or makes a model request.

Payment DTO has `id,version,displayEnabled,label,status,mode,ready,connected:false,chargesEnabled:false,externalCallsEnabled:false,secretConfigured:false,configurationOnly:true,environment:"SIMULATION"` and optional `updatedAt`. Labels are 1–80 characters. MOCK alone has `status:"READY",mode:"MOCK",ready:true` and defaults to `displayEnabled:true`. ALIPAY/WECHAT/BANKCARD always have `status:"NOT_CONNECTED",mode:"NOT_CONNECTED",ready:false` and default to hidden. Display flags and labels cannot connect or enable a channel or charge funds.

First updates consume default version 1 and return version 2; later updates require the current version. All accepted writes create immutable `platformConfigHistory` snapshots with actor, request, version and hash, and an audit event. Input DTOs accept only the listed fields. No API URL, key, certificate, password, outbound client, connection-test success, production mode, real payment, credential storage or server-side remote fetch is implemented.

## Persistence and verification boundary

`supportResources`, `resourceHistory`, `supportAttachments`, `supportPlatformConfig` and `platformConfigHistory` live inside the existing `JdbcSimStore` JSON aggregate. The existing transaction lock and transaction rollback apply, and the history collections are append-only protected. This is real persistence when the existing Java simulation service uses its configured MySQL datasource, but it is not new normalized production tables, durable object storage, database-level authorization, production malware protection or an independent frontend/backend acceptance result. Static built-ins and default configs need no writes.

Tests exercise authentication, global versus scoped grants, byte/name/MIME validation, no Base64 metadata leakage, owner-only unbound files, target binding scope, attachment count/size/quota boundaries, immutable material history, stale configuration versions, exact field allowlists, serialization and persistent metadata flags. The implementation has no external service calls. Test results and UI integration verification must be reported separately; source delivery alone does not prove a running deployment.
