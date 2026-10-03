package cn.iocoder.yudao.server.simulation;

import java.time.Instant;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Isolated simulated commerce. The repository serializes and commits each command atomically. */
public final class CommerceModule implements SimModule {
    private static final Set<String> OPS = new HashSet<String>(Arrays.asList(
        "createOrder", "publishOrder", "resolveOrderCredential", "confirmOrder", "createFulfillmentCredential",
        "fulfillOrder", "cancelOrder", "getOrder", "listOrders", "getReceivable", "listReceivables"));
    public boolean supports(String op) { return OPS.contains(op); }
    public Object execute(String op, SimContext c, Map<String,Object> b, Map<String,String> p) {
        expireReservations(c);
        if ("createOrder".equals(op)) return createOrder(c,b);
        if ("resolveOrderCredential".equals(op)) {
            c.requireRoles("USER"); Map<String,Object> cred = credential(c,s(b,"credential"),"CONFIRM");
            Map<String,Object> order = c.get("orders",s(cred,"orderId"));
            c.check("PUBLISHED".equals(s(order,"status")),409,"ORDER_STATE_CONFLICT","订单当前不可确认");
            validateCredentialOrder(c,cred,order); return orderDto(c,order);
        }
        if ("listOrders".equals(op)) {
            c.requireRoles("USER","OWNER","CLERK"); List<Map<String,Object>> result = new ArrayList<Map<String,Object>>();
            for(Map<String,Object> o:c.all("orders")) if(canRead(c,o,"OWNER","CLERK") && matches(o,p)) result.add(orderDto(c,o));
            sort(result,p); return c.page(result,p);
        }
        if ("listReceivables".equals(op)) {
            c.requireRoles("USER","OWNER","LEDGER_CHECKER"); List<Map<String,Object>> result = new ArrayList<Map<String,Object>>();
            for(Map<String,Object> r:c.all("receivables")) if(canRead(c,r,"OWNER","LEDGER_CHECKER") && matches(r,p)) result.add(receivableDto(c,r));
            sort(result,p); return c.page(result,p);
        }
        if ("getReceivable".equals(op)) {
            Map<String,Object> r=c.get("receivables",p.get("id")); requireRead(c,r,"OWNER","LEDGER_CHECKER"); return receivableDto(c,r);
        }
        Map<String,Object> o = c.get("orders",p.get("id"));
        if ("getOrder".equals(op)) {requireRead(c,o,"OWNER","CLERK"); return orderDto(c,o);}
        if ("publishOrder".equals(op)) return publish(c,o,b);
        if ("confirmOrder".equals(op)) return confirm(c,o,b);
        if ("createFulfillmentCredential".equals(op)) {
            requireOwner(c,o); c.version(o,b); c.check("CONFIRMED".equals(s(o,"status")),409,"ORDER_STATE_CONFLICT","订单尚未确认或预占已失效");
            return issueCredential(c,o,"FULFILL");
        }
        if ("fulfillOrder".equals(op)) return fulfill(c,o,b);
        if ("cancelOrder".equals(op)) return cancel(c,o,b);
        throw new IllegalArgumentException("Unsupported operation: "+op);
    }
    private Object createOrder(SimContext c, Map<String,Object> b) {
        noNewCredit(c); Map<String,Object> store=c.get("stores",s(b,"storeId"));
        c.requireScope(s(store,"merchantUid"),s(store,"id"),null,"OWNER","CLERK"); activeMerchant(c,s(store,"merchantUid"));
        c.check(active(store),409,"STORE_INACTIVE","门店已停用");
        List<Map<String,Object>> inputs=list(b,"items"), items=new ArrayList<Map<String,Object>>();
        c.check(!inputs.isEmpty() && inputs.size()<=20,422,"VALIDATION_ERROR","订单需包含1至20项商品");
        String fingerprint=c.hashObject(b);
        for(Map<String,Object> prior:c.all("orders")) if(s(store,"merchantUid").equals(s(prior,"merchantUid")) && s(b,"clientReference").equals(s(prior,"clientReference"))) {
            c.check(fingerprint.equals(s(prior,"createHash")),409,"IDEMPOTENCY_CONFLICT","商家请求号已对应其他内容"); return orderDto(c,prior);
        }
        long total=0; Set<String> seen=new HashSet<String>(); Map<String,Object> versions=new LinkedHashMap<String,Object>();
        for(Map<String,Object> input:inputs) {
            String productId=s(input,"productId"); c.check(seen.add(productId),422,"DUPLICATE_PRODUCT","同一商品请合并数量");
            Map<String,Object> product=c.get("products",productId);
            c.check(s(store,"id").equals(s(product,"storeId")),403,"SCOPE_FORBIDDEN","商品不属于本门店");
            c.check(active(product),409,"PRODUCT_INACTIVE","商品已下架");
            c.check(n(product,"version")==n(input,"productVersion"),409,"VERSION_CONFLICT","商品已更新，请重新读取");
            long q=n(input,"quantity"), price=n(product,"priceMinor");
            c.check(q>=1 && q<=99 && price>0,422,"VALIDATION_ERROR","数量或商品价格无效");
            long subtotal=Math.multiplyExact(q,price); total=Math.addExact(total,subtotal);
            items.add(map("productId",productId,"name",s(product,"name"),"quantity",q,"unitPriceMinor",price,"subtotalMinor",subtotal));
            versions.put(productId,n(product,"version"));
        }
        Map<String,Object> rule=currentRule(c); c.check(total>0 && total<=n(rule,"transactionMaxMinor"),422,"TRANSACTION_LIMIT_EXCEEDED","超过当前模拟单笔上限");
        Map<String,Object> o=c.create("orders",map("merchantUid",s(store,"merchantUid"),"storeId",s(store,"id"),"bookId",FinanceModule.bookFor(c,s(store,"merchantUid")),
            "totalMinor",total,"currency","CNY","status","DRAFT","fulfillmentStatus","NOT_READY","items",items,"productVersions",versions,
            "ruleCode",s(rule,"code"),"ruleSnapshot",new LinkedHashMap<String,Object>(rule),"creatorId",c.actorId(),"clientReference",s(b,"clientReference"),
            "createHash",fingerprint,"environment","SIMULATION","refundedMinor",0L,"adjustedMinor",0L));
        o.put("snapshotHash",snapshotHash(c,o)); c.audit("ORDER_CREATED","orders",s(o,"id"),map("totalMinor",total)); return orderDto(c,o);
    }
    private Object publish(SimContext c,Map<String,Object> o,Map<String,Object> b) {
        c.requireScope(s(o,"merchantUid"),s(o,"storeId"),s(o,"bookId"),"OWNER","CLERK"); noNewCredit(c); activeMerchant(c,s(o,"merchantUid"));
        c.version(o,b); c.check("DRAFT".equals(s(o,"status")),409,"ORDER_STATE_CONFLICT","仅草稿订单可以发布");
        Map<String,Object> store=c.get("stores",s(o,"storeId")); c.check(active(store),409,"STORE_INACTIVE","门店已停用");
        validateProducts(c,o); o.put("status","PUBLISHED"); c.bump(o);
        Map<String,Object> cred=issueCredential(c,o,"CONFIRM"); o.put("expiresAt",cred.get("expiresAt"));
        c.audit("ORDER_PUBLISHED","orders",s(o,"id"),map("snapshotHash",s(o,"snapshotHash"))); return cred;
    }
    private Object confirm(SimContext c,Map<String,Object> o,Map<String,Object> b) {
        c.requireRoles("USER"); noNewCredit(c); activeMerchant(c,s(o,"merchantUid")); c.version(o,b);
        c.check("PUBLISHED".equals(s(o,"status")),409,"ORDER_STATE_CONFLICT","订单不可重复确认");
        c.check(bool(b,"confirmed"),422,"CONFIRMATION_REQUIRED","需要逐笔主动确认");
        c.check(s(o,"snapshotHash").equals(s(b,"snapshotHash")),409,"SNAPSHOT_CHANGED","订单快照已改变，请重新读取");
        c.check(Objects.equals(str(c.state.meta.get("agreementVersion")),s(b,"agreementVersion")),409,"AGREEMENT_VERSION_CHANGED","模拟协议版本已变更");
        Map<String,Object> cred=credential(c,s(b,"credential"),"CONFIRM"); validateCredentialOrder(c,cred,o); validateProducts(c,o);
        Map<String,Object> a=account(c,c.actorUid()); refreshAccount(c,c.actorUid());
        c.check(!bool(a,"blocked"),409,"ACCOUNT_BLOCKED","新增模拟消费已冻结，历史查询还款和争议仍可操作");
        Map<String,Object> rule=ruleSnapshot(o); long total=n(o,"totalMinor");
        c.check(total<=n(rule,"transactionMaxMinor"),422,"TRANSACTION_LIMIT_EXCEEDED","超过模拟单笔上限");
        c.check(n(a,"availableMinor")>=total,409,"INSUFFICIENT_QUOTA","跨店共享模拟可用额度不足");
        Map<String,Object> r=c.create("reservations",map("orderId",s(o,"id"),"userUid",c.actorUid(),"status","HELD","amountMinor",total,
            "expiresAt",c.now().plusSeconds(n(rule,"reservationTtlSeconds")).toString(),"environment","SIMULATION"));
        o.put("userUid",c.actorUid()); o.put("userId",c.actorId()); o.put("reservationId",s(r,"id")); o.put("status","CONFIRMED");
        o.put("fulfillmentStatus","READY"); o.put("confirmedAt",c.now().toString()); o.put("agreementVersion",s(b,"agreementVersion")); c.bump(o);
        consumeCredential(c,cred); refreshAccount(c,c.actorUid());
        c.audit("ORDER_CONFIRMED","orders",s(o,"id"),map("reservationId",s(r,"id"),"agreementVersion",s(b,"agreementVersion"),"snapshotHash",s(b,"snapshotHash")));
        c.notify(c.actorId(),"模拟订单已确认","orders",s(o,"id"));
        return map("order",orderDto(c,o),"reservation",reservationDto(c,r),"account",accountDto(c,a));
    }
    private Object fulfill(SimContext c,Map<String,Object> o,Map<String,Object> b) {
        c.requireScope(s(o,"merchantUid"),s(o,"storeId"),s(o,"bookId"),"OWNER","CLERK"); c.version(o,b);
        c.check("CONFIRMED".equals(s(o,"status")) && "READY".equals(s(o,"fulfillmentStatus")),409,"ORDER_STATE_CONFLICT","订单不可核销");
        Map<String,Object> cred=credential(c,s(b,"credential"),"FULFILL"); validateCredentialOrder(c,cred,o);
        c.check(Objects.equals(s(cred,"userUid"),s(o,"userUid")),403,"CREDENTIAL_OWNER_MISMATCH","核销凭证用户不一致");
        Map<String,Object> reservation=c.get("reservations",s(o,"reservationId"));
        c.check("HELD".equals(s(reservation,"status")) && c.now().isBefore(Instant.parse(s(reservation,"expiresAt"))),409,"RESERVATION_EXPIRED","预占已失效");
        for(Map<String,Object> existing:c.all("receivables")) c.check(!s(o,"id").equals(s(existing,"orderId")),409,"ALREADY_FULFILLED","订单已生成应收");
        long amount=n(o,"totalMinor");
        Map<String,Object> r=c.create("receivables",map("orderId",s(o,"id"),"merchantUid",s(o,"merchantUid"),"storeId",s(o,"storeId"),"bookId",s(o,"bookId"),
            "userUid",s(o,"userUid"),"userId",s(o,"userId"),"issuedMinor",amount,"outstandingMinor",amount,"status","OPEN",
            "dueAt",c.now().plusSeconds(Math.multiplyExact(n(ruleSnapshot(o),"termDays"),86400L)).toString(),"overdue",false,"hasOpenDispute",false,"environment","SIMULATION"));
        Map<String,Object> j=FinanceModule.postJournal(c,s(o,"merchantUid"),s(o,"userUid"),"J-01","orders",s(o,"id"),"fulfill:"+s(o,"id"),c.now().toString(),
            Arrays.asList(FinanceModule.line("SIM_AR","DR",amount,s(r,"id"),s(o,"id")),FinanceModule.line("SIM_SALES_CTRL","CR",amount,s(r,"id"),s(o,"id"))));
        r.put("originalJournalId",s(j,"id")); o.put("receivableId",s(r,"id")); o.put("status","FULFILLED"); o.put("fulfillmentStatus","FULFILLED"); o.put("fulfilledAt",c.now().toString()); c.bump(o);
        reservation.put("status","CONSUMED"); c.bump(reservation); consumeCredential(c,cred); refreshAccount(c,s(o,"userUid"));
        c.notify(s(o,"userId"),"模拟账单已生成","receivables",s(r,"id")); c.audit("ORDER_FULFILLED","orders",s(o,"id"),map("receivableId",s(r,"id"),"journalId",s(j,"id")));
        return receivableDto(c,r);
    }
    private Object cancel(SimContext c,Map<String,Object> o,Map<String,Object> b) {
        c.check(isOwner(c,o) || c.inScope(s(o,"merchantUid"),s(o,"storeId"),s(o,"bookId"),"OWNER"),403,"SCOPE_FORBIDDEN","无权取消该订单");
        if("CANCELLED".equals(s(o,"status"))) return orderDto(c,o);
        c.version(o,b); c.check(Arrays.asList("DRAFT","PUBLISHED","CONFIRMED").contains(s(o,"status")),409,"ORDER_STATE_CONFLICT","履约订单须走退款流程");
        release(c,o,"CANCELLED","RELEASED"); c.audit("ORDER_CANCELLED","orders",s(o,"id"),map("reason",s(b,"reason"))); return orderDto(c,o);
    }
    public static void expireReservations(SimContext c) {
        for(Map<String,Object> o:c.all("orders")) {
            if("PUBLISHED".equals(s(o,"status")) && o.get("expiresAt")!=null && !c.now().isBefore(Instant.parse(s(o,"expiresAt")))) release(c,o,"EXPIRED","EXPIRED");
            else if("CONFIRMED".equals(s(o,"status"))) {
                Map<String,Object> r=c.get("reservations",s(o,"reservationId"));
                if("HELD".equals(s(r,"status")) && !c.now().isBefore(Instant.parse(s(r,"expiresAt")))) release(c,o,"EXPIRED","EXPIRED");
            }
        }
    }
    private static void release(SimContext c,Map<String,Object> o,String orderStatus,String reservationStatus) {
        if(o.get("reservationId")!=null) {
            Map<String,Object> r=c.get("reservations",s(o,"reservationId"));
            if("HELD".equals(s(r,"status"))) {r.put("status",reservationStatus); c.bump(r);}
        }
        o.put("status",orderStatus); o.put("fulfillmentStatus","VOID"); c.bump(o);
        for(Map<String,Object> cred:c.all("credentials")) if(s(o,"id").equals(s(cred,"orderId")) && !bool(cred,"used")) {cred.put("revoked",true); c.bump(cred);}
        if(o.get("userUid")!=null) {refreshAccount(c,s(o,"userUid")); c.notify(s(o,"userId"),"模拟订单已取消或到期","orders",s(o,"id"));}
    }
    private static Map<String,Object> issueCredential(SimContext c,Map<String,Object> o,String purpose) {
        for(Map<String,Object> cred:c.all("credentials")) if(s(o,"id").equals(s(cred,"orderId")) && purpose.equals(s(cred,"purpose")) && !bool(cred,"used")) {cred.put("revoked",true); c.bump(cred);}
        String raw=c.token(); Instant expires=c.now().plusSeconds(n(ruleSnapshot(o),"credentialTtlSeconds"));
        if("FULFILL".equals(purpose)) {Instant reserved=Instant.parse(s(c.get("reservations",s(o,"reservationId")),"expiresAt")); if(reserved.isBefore(expires)) expires=reserved;}
        c.create("credentials",map("tokenHash",c.hash(raw),"purpose",purpose,"orderId",s(o,"id"),"orderVersion",n(o,"version"),"userUid",s(o,"userUid"),"expiresAt",expires.toString(),"used",false,"revoked",false));
        return map("credential",raw,"purpose",purpose,"orderId",s(o,"id"),"orderVersion",n(o,"version"),"expiresAt",expires.toString());
    }
    private static Map<String,Object> credential(SimContext c,String token,String purpose) {
        c.check(token!=null && !token.isEmpty(),422,"CREDENTIAL_REQUIRED","缺少短凭证"); String hash=c.hash(token);
        for(Map<String,Object> cred:c.all("credentials")) if(hash.equals(s(cred,"tokenHash"))) {
            c.check(purpose.equals(s(cred,"purpose")),403,"CREDENTIAL_PURPOSE_MISMATCH","凭证用途错误");
            c.check(!bool(cred,"used") && !bool(cred,"revoked"),409,"CREDENTIAL_CONSUMED","凭证已使用或已失效");
            c.check(c.now().isBefore(Instant.parse(s(cred,"expiresAt"))),410,"CREDENTIAL_EXPIRED","凭证已过期"); return cred;
        }
        c.check(false,404,"CREDENTIAL_NOT_FOUND","凭证无效"); return null;
    }
    private static void validateCredentialOrder(SimContext c,Map<String,Object> cred,Map<String,Object> o) {
        c.check(s(o,"id").equals(s(cred,"orderId")) && n(o,"version")==n(cred,"orderVersion"),409,"CREDENTIAL_ORDER_MISMATCH","凭证不对应当前订单版本");
    }
    private static void consumeCredential(SimContext c,Map<String,Object> cred) {cred.put("used",true); cred.put("consumedAt",c.now().toString()); c.bump(cred);}
    private static void validateProducts(SimContext c,Map<String,Object> o) {
        Map<String,Object> versions=objectMap(o,"productVersions");
        for(Map<String,Object> item:CommerceModule.<Map<String,Object>>values(o,"items")) {
            Map<String,Object> product=c.get("products",s(item,"productId"));
            c.check(active(product) && n(product,"version")==n(versions,s(item,"productId")),409,"PRODUCT_VERSION_CHANGED","商品已变更，请重新创建并展示订单；原快照保持不变");
        }
    }
    public static Map<String,Object> currentRule(SimContext c) {
        String id=str(c.state.meta.get("ruleId")); c.check(id!=null,409,"RULE_NOT_CONFIGURED","尚未配置模拟规则夹具");
        Map<String,Object> rule=c.get("rules",id); c.check("APPROVED".equals(s(rule,"status")),409,"RULE_NOT_APPROVED","模拟规则未批准"); return rule;
    }
    public static Map<String,Object> account(SimContext c,String uid) {
        for(Map<String,Object> a:c.all("accounts")) if(uid.equals(s(a,"userUid"))) return a;
        c.check(false,409,"SIMULATION_NOT_ACTIVATED","请先激活模拟账户"); return null;
    }
    public static void refreshAccount(SimContext c,String uid) {
        Map<String,Object> a=account(c,uid); long reserved=0,principal=0; boolean frozen=bool(a,"manualBlocked");
        for(Map<String,Object> r:c.all("reservations")) if(uid.equals(s(r,"userUid")) && "HELD".equals(s(r,"status"))) reserved=Math.addExact(reserved,n(r,"amountMinor"));
        for(Map<String,Object> r:c.all("receivables")) if(uid.equals(s(r,"userUid"))) {principal=Math.addExact(principal,n(r,"outstandingMinor")); if(n(r,"outstandingMinor")>0 && !c.now().isBefore(Instant.parse(s(r,"dueAt")))) frozen=true;}
        for(Map<String,Object> d:c.all("disputes")) if(uid.equals(s(d,"userUid")) && Arrays.asList("OPEN","REVIEWING","APPEALED").contains(s(d,"status"))) frozen=true;
        long available=n(a,"totalMinor")-reserved-principal;
        c.check(reserved>=0 && principal>=0 && available>=0,409,"ACCOUNT_INVARIANT_VIOLATION","模拟额度投影不守恒");
        if(reserved!=n(a,"reservedMinor") || principal!=n(a,"principalMinor") || available!=n(a,"availableMinor") || frozen!=bool(a,"blocked")) {
            a.put("reservedMinor",reserved); a.put("principalMinor",principal); a.put("availableMinor",available); a.put("blocked",frozen); c.bump(a);
        }
    }
    public static void activeMerchant(SimContext c,String uid) {
        Map<String,Object> merchant=null; for(Map<String,Object> m:c.all("merchants")) if(uid.equals(s(m,"uid"))) {merchant=m;break;}
        c.check(merchant!=null && "APPROVED".equals(s(merchant,"status")),409,"MERCHANT_NOT_ACTIVE","商家不可新增模拟交易");
    }
    public static void noNewCredit(SimContext c) {c.check(!Boolean.TRUE.equals(c.state.meta.get("emergencyStopped")),409,"SIMULATION_PAUSED","新增模拟交易已暂停");}
    public static boolean active(Map<String,Object> r) {return r.containsKey("active")?bool(r,"active"):Arrays.asList("ACTIVE","APPROVED").contains(s(r,"status"));}
    public static boolean isOwner(SimContext c,Map<String,Object> r) {return c.actorUid().equals(s(r,"userUid"));}
    public static void requireOwner(SimContext c,Map<String,Object> r) {c.requireRoles("USER"); c.check(isOwner(c,r),403,"SCOPE_FORBIDDEN","仅本人可操作");}
    public static boolean canRead(SimContext c,Map<String,Object> r,String... roles) {return isOwner(c,r) || c.inScope(s(r,"merchantUid"),s(r,"storeId"),s(r,"bookId"),roles);}
    public static void requireRead(SimContext c,Map<String,Object> r,String... roles) {c.check(canRead(c,r,roles),404,"NOT_FOUND","资源不存在或不可访问");}
    public static Map<String,Object> orderDto(SimContext c,Map<String,Object> o) {return c.project(o,"id","merchantUid","storeId","userUid","totalMinor","currency","status","fulfillmentStatus","version","snapshotHash","ruleCode","items","createdAt");}
    public static Map<String,Object> reservationDto(SimContext c,Map<String,Object> r) {return c.project(r,"id","orderId","status","amountMinor","expiresAt");}
    public static Map<String,Object> accountDto(SimContext c,Map<String,Object> a) {return c.project(a,"userUid","totalMinor","reservedMinor","principalMinor","availableMinor","blocked","version");}
    public static Map<String,Object> receivableDto(SimContext c,Map<String,Object> r) {
        Map<String,Object> out=c.project(r,"id","orderId","userUid","merchantUid","issuedMinor","outstandingMinor","status","dueAt","hasOpenDispute","version");
        out.put("overdue",n(r,"outstandingMinor")>0 && !c.now().isBefore(Instant.parse(s(r,"dueAt")))); return out;
    }
    private static String snapshotHash(SimContext c,Map<String,Object> o) {return c.hashObject(c.project(o,"merchantUid","storeId","totalMinor","currency","items","ruleCode","ruleSnapshot","productVersions"));}
    @SuppressWarnings("unchecked") public static Map<String,Object> objectMap(Map<String,Object> r,String key) {return (Map<String,Object>)r.get(key);}
    private static Map<String,Object> ruleSnapshot(Map<String,Object> o) {return objectMap(o,"ruleSnapshot");}
    @SuppressWarnings("unchecked") public static <T> List<T> values(Map<String,Object> r,String key) {Object v=r.get(key); return v instanceof List?(List<T>)v:Collections.<T>emptyList();}
    private static boolean matches(Map<String,Object> r,Map<String,String> p) {
        if(p.get("status")!=null && !p.get("status").equals(s(r,"status"))) return false;
        String date=s(r,"createdAt").substring(0,10); return (p.get("dateFrom")==null || date.compareTo(p.get("dateFrom"))>=0) && (p.get("dateTo")==null || date.compareTo(p.get("dateTo"))<=0);
    }
    private static void sort(List<Map<String,Object>> records,final Map<String,String> p) {Collections.sort(records,new Comparator<Map<String,Object>>() {public int compare(Map<String,Object> a,Map<String,Object> b) {int order=Long.compare(n(a,"id"),n(b,"id"));return "createdAt_asc".equals(p.get("sort"))?order:-order;}});}
}
