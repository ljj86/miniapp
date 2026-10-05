# Shop support HTTP contract (working iteration)

Base: `/api/v1`. Success: `{data,meta:{requestId,serverTime,environment:"SIMULATION"}}`; failure: HTTP 4xx `{code,message,requestId}`. All support APIs require `Authorization: Bearer <server-issued accessToken>`. Every POST requires a new 16–128-character `Idempotency-Key`; retry the same action with the same key/body. There is no request actor/userId/replyUserId or local-storage identity trust. No production authentication or real funds are provided.

Login remains the original simulation OTP flow: POST `/auth/sms-challenges` `{phone:"SIM-USER-001",purpose:"LOGIN"}`, then POST `/auth/sessions` `{provider:"PHONE_OTP",phone,challengeId,code:"246810"}`. Other fixtures: `SIM-OWNER-001` (store grant only after approved merchant onboarding), `SIM-SUPPORT-001` (global SUPPORT). The 111/111 browser demo is a separate experience.

## Identity and IDs

GET `/support/context`: `{actor:{id,uid,name,role},shops:[{id,unitId,merchantUid,name,active}],orders:[{id,unitId,status}],capabilities}`. role is `ROLE_USER`, `ROLE_UNIT`, `ROLE_ADMIN`, derived from active server grants. OWNER/CLERK can access only their granted shops; global SUPPORT is platform support. unitId equals the **real server store ID**, never the local demo merchant/account ID. All IDs are strings. UI must select/map server shops explicitly rather than substituting local ID 1. Server DTO sender fields: senderId/senderRole/senderName/senderLabel/senderAvatar.

## Conversations / tickets

- GET `/support/overview` optional q (title/content/customer/shop, max100), unitId (real store ID), status, priority, includeArchived=true, page (default1), limit (10/20/50/100, default20): `{sessions,tickets,counts,page,limit,sessionTotal,ticketTotal,truncated,hasMore}`. Counts are scoped. DTOs include customerName/customerAvatar/shopName/shopAvatar; sessions unread/unreadCount/lastMessage; tickets availableStatuses/canReply/replyCount/historyCount/archived (list entries omit replies/history)
- GET `/support/session?id=<id>` optional beforeId: `{session,messages,hasMore}` newest 100 messages in chronological order; beforeId paginates older messages. Reading does not mark messages read
- GET `/support/ticket?id=<id>` → ticket
- POST `/support/open-session` `{unitId}` → session (only customer; reuse existing customer/shop session)
- POST `/support/send-message` `{sessionId,text?,attachmentIds?:string[]}` → message. Empty text requires attachment; max5 attachments/1MiB total
- POST `/support/mark-read` `{sessionId,lastSeenMessageId}` → session. Uses the last message actually displayed, not server-latest; does not clear concurrent/new messages. A cursor must belong to this session
- POST `/support/create-ticket` `{unitId,title,content,priority:"LOW"|"MEDIUM"|"HIGH",orderId?,attachmentIds?}` → compact ticket with detailRequired:true; title≤200 codepoints, content≤1500; legacy NORMAL/IMPORTANT accepted and normalized to MEDIUM/HIGH
- POST `/support/reply-ticket` `{ticketId,text,version,attachmentIds?}` → compact ticket with detailRequired:true
- POST `/support/ticket-status` `{ticketId,status,version}` → compact ticket with detailRequired:true
- POST `/support/archive-ticket` `{ticketId,version,reason}` → compact ticket with detailRequired:true; owner customer or platform only; preserves underlying status
- POST `/support/restore-ticket` `{ticketId,version,reason}` → compact ticket with detailRequired:true; owner customer or platform only

Statuses OPEN→IN_PROGRESS→RESOLVED→CLOSED for staff. Customer can close RESOLVED, or reopen RESOLVED/CLOSED to OPEN. Staff can also reopen RESOLVED/CLOSED to OPEN. No replies/status changes to archived tickets; no reply to CLOSED. version conflicts return 409 and never overwrite. Each ticket change appends immutable server-actor history; archive is recoverable and does not delete history. Only the owning customer and global platform SUPPORT can read archived content or restore; merchants cannot. Attachments are opaque IDs from upload, never caller URLs/paths. All mutation validation is transactional. Service actions do not alter credit/order/receivable/refund amounts.

Resource/attachment/config routes are frozen in `SHOP-RESOURCE-CONTRACT.md`. Attachments use JSON base64 upload and authenticated JSON download; metadata carries opaque IDs only. No third-party URL/path is accepted. Persistence uses the existing locked `sim_v1_partition` JSON aggregate. It is a real Java/JDBC simulation store with durable transactions, not a normalized production support database. A full deployment and production identity/storage migration remain outside this iteration.

## Personal display profile

GET `/support/profile`; POST same `{version,nickname,avatarUrl,email,contactPhone}`. Own authenticated actor only; no user/actor IDs accepted. nickname1–80 codepoints; optional email<=254 and basic email validation; optional contactPhone<=32 permitted phone punctuation/digits; avatarUrl currently only `/avatar.svg`. Result adds id,uid,username (synthetic fixture account label),loginPhone (masked),identityReadOnly=true,environment. Stored in separate supportProfiles; login credentials/phone lookup/UID/finance identity are unchanged. Other support users only see nickname/avatar, never this email or contactPhone. No third-party avatar URL/upload is accepted. This is an account display profile, not a shop contact profile.

## Compact mutation results and detail refresh

Ticket commands return compact current-ticket fields, replyCount/historyCount and `detailRequired:true`, omitting replies/history. Overview entries use the same compact projection without detailRequired. `GET /support/ticket?id=…` returns complete replies/history. After a ticket write, the HTTP adapter fetches that detail explicitly. If the write succeeds but detail refresh fails, report saved-with-refresh-pending and do not silently repeat the write. This keeps idempotency storage linear in commands instead of retaining an ever-growing full history snapshot for every reply. Counts include preserved legacy records when migrating from an earlier development snapshot.

Support aggregate schema metadata is version1. A partition without support metadata is upgraded lazily on the first successful support mutation; unknown future versions fail closed rather than being overwritten. No normalized SQL table migration is implied.
