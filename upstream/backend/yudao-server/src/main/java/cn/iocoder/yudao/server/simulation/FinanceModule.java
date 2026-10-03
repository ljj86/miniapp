package cn.iocoder.yudao.server.simulation;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static cn.iocoder.yudao.server.simulation.CommerceModule.*;

/** Integer-cent, append-only SIMULATION accounting. No real payment provider or financial product. */
public final class FinanceModule implements SimModule {
    private static final ZoneId BOOK_ZONE=ZoneId.of("Asia/Shanghai");
    private static final Set<String> OPS=new HashSet<String>(Arrays.asList("createRepayment","getRepayment","createOfflineRepayment","reviewOfflineRepayment",
        "emitMockResult","receiveMockCallback","createRefund","getRefund","reviewRefund","retryRefund","createDispute","getDispute","resolveDispute","appealDispute",
        "createAdjustment","reviewAdjustment","postAdjustment","createRule","reviewRule","getCurrentRule"));
    public boolean supports(String op) {return OPS.contains(op);}
    public Object execute(String op,SimContext c,Map<String,Object> b,Map<String,String> p) {
        if("emitMockResult".equals(op)) return simulateProviderResult(c,b,true);
        // The HTTP boundary verifies SIM-HMAC-1 against raw UTF-8 bytes and claims its nonce before this operation.
        if("receiveMockCallback".equals(op)) return callback(c,b);
        expireDisputes(c);
        if("createRepayment".equals(op)) {c.requireRoles("USER");return repaymentDto(c,newRepayment(c,b,c.actorUid(),"MOCK_ONLINE"));}
        if("getRepayment".equals(op)) {Map<String,Object> r=c.get("repayments",p.get("id"));requireRead(c,r,"OWNER","LEDGER_CHECKER");reconcileProviderResults(c,s(r,"reference"));return repaymentDto(c,r);}
        if("createOfflineRepayment".equals(op)) return offlineCreate(c,b);
        if("reviewOfflineRepayment".equals(op)) return offlineReview(c,c.get("offline",p.get("id")),b);
        if("createRefund".equals(op)) return refundCreate(c,b);
        if("getRefund".equals(op)) {Map<String,Object> r=c.get("refunds",p.get("id"));requireRead(c,r,"OWNER","LEDGER_CHECKER");reconcileProviderResults(c,s(r,"reference"));return refundDto(c,r);}
        if("reviewRefund".equals(op)) return refundReview(c,c.get("refunds",p.get("id")),b);
        if("retryRefund".equals(op)) return refundRetry(c,c.get("refunds",p.get("id")),b);
        if("createDispute".equals(op)) return disputeCreate(c,b);
        if("getDispute".equals(op)) {Map<String,Object> d=c.get("disputes",p.get("id"));requireRead(c,d,"OWNER","SUPPORT");return disputeDto(c,d);}
        if("resolveDispute".equals(op)) return disputeResolve(c,c.get("disputes",p.get("id")),b);
        if("appealDispute".equals(op)) return disputeAppeal(c,c.get("disputes",p.get("id")),b);
        if("createAdjustment".equals(op)) return adjustmentCreate(c,b);
        if("reviewAdjustment".equals(op)) return adjustmentReview(c,c.get("adjustments",p.get("id")),b);
        if("postAdjustment".equals(op)) return adjustmentPost(c,c.get("adjustments",p.get("id")),b);
        if("createRule".equals(op)) return ruleCreate(c,b);
        if("reviewRule".equals(op)) return ruleReview(c,c.get("rules",p.get("id")),b);
        if("getCurrentRule".equals(op)) {c.requireRoles("USER");return ruleDto(c,currentRule(c));}
        throw new IllegalArgumentException("Unsupported operation: "+op);
    }
    private Map<String,Object> newRepayment(SimContext c,Map<String,Object> b,String uid,String method) {
        String merchant=s(b,"merchantUid"); long amount=positive(c,b,"amountMinor",100000000L);
        List<Map<String,Object>> rs=selectedReceivables(c,values(b,"receivableIds"),uid,merchant);
        long outstanding=0; List<String> ids=new ArrayList<String>(); List<Map<String,Object>> intent=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> r:rs) {outstanding=Math.addExact(outstanding,n(r,"outstandingMinor"));ids.add(s(r,"id"));intent.add(map("receivableId",s(r,"id"),"outstandingMinor",n(r,"outstandingMinor"),"version",n(r,"version")));}
        c.check(outstanding>0,409,"NO_OUTSTANDING_PRINCIPAL","所选应收已经结清");
        // Excess is retained as simulated return payable, never as freely spendable credit.
        Map<String,Object> r=c.create("repayments",map("merchantUid",merchant,"bookId",bookFor(c,merchant),"userUid",uid,"userId",userId(c,uid),"creatorId",c.actorId(),
            "amountMinor",amount,"receivedMinor",0L,"unallocatedMinor",0L,"allocatedMinor",0L,"status","PENDING","method",method,"receivableIds",ids,"intendedAllocations",intent,
            "environment","SIMULATION","currency","CNY"));
        r.put("reference","sim-payment-"+s(r,"id"));
        if("MOCK_ONLINE".equals(method)) c.create("outbox",map("eventKey","submit:"+s(r,"reference"),"reference",s(r,"reference"),"type","MOCK_PAYMENT_INTENT","status","PENDING","attempts",0L,"environment","SIMULATION"));
        c.audit("REPAYMENT_CREATED","repayments",s(r,"id"),map("amountMinor",amount,"method",method));return r;
    }
    private Object offlineCreate(SimContext c,Map<String,Object> b) {
        String merchant=s(b,"merchantUid"); c.requireScope(merchant,null,bookFor(c,merchant),"OWNER","CLERK");
        List<Map<String,Object>> selected=selectedReceivables(c,values(b,"receivableIds"),s(b,"userUid"),merchant);
        for(Map<String,Object> r:selected) c.requireScope(merchant,s(r,"storeId"),s(r,"bookId"),"OWNER","CLERK");
        evidence(c,b,true); Instant occurred=parseInstant(c,s(b,"occurredAt"));c.check(!occurred.isAfter(c.now()),422,"FUTURE_EVENT","登记时间不可晚于当前时间");
        Map<String,Object> r=newRepayment(c,b,s(b,"userUid"),"MOCK_OFFLINE");
        Map<String,Object> d=c.create("offline",map("repaymentId",s(r,"id"),"merchantUid",merchant,"bookId",s(r,"bookId"),"userUid",s(r,"userUid"),"userId",s(r,"userId"),
            "creatorId",c.actorId(),"status","SUBMITTED","occurredAt",occurred.toString(),"reason",s(b,"reason"),"evidenceIds",new ArrayList<Object>(values(b,"evidenceIds"))));
        c.notify(s(r,"userId"),"请核实模拟线下登记","offline",s(d,"id"));return offlineDto(c,d);
    }
    private Object offlineReview(SimContext c,Map<String,Object> d,Map<String,Object> b) {
        c.independent(s(d,"creatorId")); if(!isOwner(c,d)) c.requireScope(s(d,"merchantUid"),null,s(d,"bookId"),"LEDGER_CHECKER");
        c.version(d,b);state(c,d,"SUBMITTED");evidence(c,b,false); Map<String,Object> r=c.get("repayments",s(d,"repaymentId"));
        if(approve(b)) {applyReceipt(c,r,n(r,"amountMinor"),"offline:"+s(d,"id"),s(d,"occurredAt"),false);d.put("status","VERIFIED");}
        else {r.put("status","CLOSED");c.bump(r);d.put("status","REJECTED");}
        d.put("checkerId",c.actorId());d.put("decisionReason",s(b,"reason"));d.put("decisionEvidenceIds",new ArrayList<Object>(values(b,"evidenceIds")));c.bump(d);
        c.audit("OFFLINE_REVIEWED","offline",s(d,"id"),map("decision",s(b,"decision"),"repaymentId",s(r,"id")));return offlineDto(c,d);
    }
    /** Separate authoritative simulator truth enables loss-of-notification recovery without any network. */
    public static Map<String,Object> simulateProviderResult(SimContext c,Map<String,Object> b,boolean deliverCallback) {
        c.requireRoles("SIM_CONTROLLER");
        c.check("SIMULATION".equals(s(b,"environment")) && "CNY".equals(s(b,"currency")),422,"SIMULATION_ONLY","仅接受CNY模拟事件");
        long amount=positive(c,b,"amountMinor",100000000L);c.check(!parseInstant(c,s(b,"occurredAt")).isAfter(c.now()),422,"FUTURE_EVENT","模拟事件不得发生在未来");
        boolean payment="PAYMENT_CONFIRMED".equals(s(b,"eventType"));c.check(payment || "REFUND_CONFIRMED".equals(s(b,"eventType")),422,"VALIDATION_ERROR","模拟事件类型无效");
        Map<String,Object> target=find(c,payment?"repayments":"refunds","reference",s(b,"reference"));c.check(target!=null,404,"UNKNOWN_REFERENCE","模拟单据不存在");
        c.requireScope(s(target,"merchantUid"),null,s(target,"bookId"),"SIM_CONTROLLER");
        c.check(Objects.equals(s(b,"merchantUid"),s(target,"merchantUid")) && amount==(payment?n(target,"amountMinor"):n(target,"returnPayableMinor")),409,"PROVIDER_RESULT_MISMATCH","模拟结果的商家或金额与原单不一致");
        c.check(!payment || "MOCK_ONLINE".equals(s(target,"method")),409,"METHOD_MISMATCH","线下登记不能由在线模拟渠道确认");
        String eventId=s(b,"providerEventId");c.check(eventId!=null && !eventId.trim().isEmpty(),422,"VALIDATION_ERROR","缺少事件编号");
        Map<String,Object> payload=canonicalMockResult(b);String hash=c.hashObject(payload);Map<String,Object> result=find(c,"providerResults","providerEventId",eventId);
        if(result!=null)c.check(hash.equals(s(result,"payloadHash")),409,"EVENT_CONTENT_CONFLICT","模拟渠道原始事件不可修改");
        else result=c.create("providerResults",map("providerEventId",eventId,"reference",s(b,"reference"),"merchantUid",s(b,"merchantUid"),"eventType",s(b,"eventType"),"payload",payload,"payloadHash",hash,"creatorId",c.actorId(),"environment","SIMULATION"));
        if(deliverCallback)new FinanceModule().callback(c,payload);
        c.audit("SIMULATOR_PROVIDER_RESULT","providerResults",s(result,"id"),map("reference",s(b,"reference"),"delivered",deliverCallback));
        return map("accepted",true,"resourceId",s(result,"id"));
    }
    /** Call only after authorizing a read of the referenced repayment/refund, inside a write transaction. */
    public static void reconcileProviderResults(SimContext c,String reference) {
        for(Map<String,Object> result:c.all("providerResults")) if(reference.equals(s(result,"reference"))) {
            Map<String,Object> payload=objectMap(result,"payload");
            c.check(payload!=null && s(result,"payloadHash").equals(c.hashObject(canonicalMockResult(payload))),409,"PROVIDER_RESULT_TAMPERED","模拟渠道原始证据校验失败");
            new FinanceModule().callback(c,payload);
        }
    }
    private Object callback(SimContext c,Map<String,Object> b) {
        c.check("SIMULATION".equals(s(b,"environment")) && "CNY".equals(s(b,"currency")),422,"SIMULATION_ONLY","仅接受CNY模拟事件");
        long amount=positive(c,b,"amountMinor",100000000L); c.check(!parseInstant(c,s(b,"occurredAt")).isAfter(c.now()),422,"FUTURE_EVENT","事件发生时间不得晚于服务器当前时间");
        String event=s(b,"providerEventId"), hash=c.hashObject(canonicalMockResult(b));
        c.check(event!=null && !event.trim().isEmpty(),422,"VALIDATION_ERROR","缺少模拟事件编号");
        for(Map<String,Object> prior:c.all("inbox")) if(event.equals(s(prior,"providerEventId"))) {
            c.check(hash.equals(s(prior,"payloadHash")),409,"EVENT_CONTENT_CONFLICT","同一事件号的正文不可变更");return "RECEIVED".equals(s(prior,"status"))?applyInbox(c,prior,b):map("accepted",true,"resourceId",s(prior,"id"));
        }
        Map<String,Object> inbox=c.create("inbox",map("providerEventId",event,"payloadHash",hash,"reference",s(b,"reference"),"eventType",s(b,"eventType"),"merchantUid",s(b,"merchantUid"),
            "amountMinor",amount,"currency","CNY","occurredAt",s(b,"occurredAt"),"environment","SIMULATION","status","RECEIVED","payload",new LinkedHashMap<String,Object>(b)));
        return applyInbox(c,inbox,b);
    }
    private Object applyInbox(SimContext c,Map<String,Object> inbox,Map<String,Object> b) {
        long amount=n(b,"amountMinor");String event=s(b,"providerEventId");
        boolean payment="PAYMENT_CONFIRMED".equals(s(b,"eventType")), refund="REFUND_CONFIRMED".equals(s(b,"eventType"));
        Map<String,Object> target=payment?find(c,"repayments","reference",s(b,"reference")):refund?find(c,"refunds","reference",s(b,"reference")):null;
        if(target==null) return callbackException(c,inbox,"UNKNOWN_REFERENCE",null);
        if(!s(b,"merchantUid").equals(s(target,"merchantUid"))) return callbackException(c,inbox,"MERCHANT_MISMATCH",target);
        long expected=payment?n(target,"amountMinor"):n(target,"returnPayableMinor");
        if(amount!=expected) return callbackException(c,inbox,"AMOUNT_MISMATCH",target);
        if(payment && !"MOCK_ONLINE".equals(s(target,"method"))) return callbackException(c,inbox,"METHOD_MISMATCH",target);
        boolean alreadyApplied=payment?n(target,"receivedMinor")>0:"SUCCEEDED".equals(s(target,"status")) && n(target,"returnedMinor")==amount;
        if(!alreadyApplied && !canPostToday(c,s(target,"bookId"))) {
            inbox.put("pendingReason","CURRENT_BUSINESS_DAY_NOT_OPEN");inbox.put("pendingSince",inbox.containsKey("pendingSince")?inbox.get("pendingSince"):c.now().toString());
            c.bump(inbox);c.audit("MOCK_EVENT_PENDING","inbox",s(inbox,"id"),map("reason","CURRENT_BUSINESS_DAY_NOT_OPEN"));return map("accepted",true,"resourceId",s(inbox,"id"));
        }
        if(payment) {
            if(n(target,"receivedMinor")>0) {inbox.put("duplicateReference",true);}
            else {
                boolean late=Arrays.asList("CLOSED","FAILED","EXCEPTION").contains(s(target,"status"));
                if(!late && !"PENDING".equals(s(target,"status"))) return callbackException(c,inbox,"PAYMENT_STATE_MISMATCH",target);
                applyReceipt(c,target,amount,"provider:"+event,s(b,"occurredAt"),late);
                if(late) difference(c,inbox,target,"LATE_SUCCESS");
            }
        } else {
            if("SUCCEEDED".equals(s(target,"status")) && n(target,"returnedMinor")==amount) inbox.put("duplicateReference",true);
            else {
                if(!Arrays.asList("PROCESSING","FAILED","EXCEPTION").contains(s(target,"status")) || n(target,"returnedMinor")>0) return callbackException(c,inbox,"REFUND_STATE_MISMATCH",target);
                Map<String,Object> o=c.get("orders",s(target,"orderId"));
                postJournal(c,s(target,"merchantUid"),s(target,"userUid"),"J-05","refunds",s(target,"id"),"refund-return:"+s(target,"id"),s(b,"occurredAt"),
                    Arrays.asList(line("SIM_RETURN_PAYABLE","DR",amount,s(o,"receivableId"),s(o,"id")),line("SIM_RECEIPT","CR",amount,s(o,"receivableId"),s(o,"id"))));
                target.put("returnedMinor",amount);target.put("status","SUCCEEDED");target.put("confirmedEventId",event);c.bump(target);
                c.notify(s(target,"userId"),"模拟退款已完成","refunds",s(target,"id"));
            }
        }
        inbox.remove("pendingReason");inbox.put("status","APPLIED");inbox.put("appliedAt",c.now().toString());inbox.put("resourceId",s(target,"id"));c.bump(inbox);
        c.audit("MOCK_EVENT_APPLIED","inbox",s(inbox,"id"),map("providerEventId",event,"reference",s(b,"reference")));return map("accepted",true,"resourceId",s(inbox,"id"));
    }
    /** Run in a separate serialized repository transaction; never re-emits an external request. */
    public static void retryPendingCallbacks(SimContext c) {
        FinanceModule module=new FinanceModule();
        for(Map<String,Object> inbox:c.all("inbox")) if("RECEIVED".equals(s(inbox,"status")) && inbox.get("payload") instanceof Map) {
            String merchant=s(inbox,"merchantUid");Map<String,Object> target=find(c,"books","merchantUid",merchant);
            if(target!=null && canPostToday(c,s(target,"id"))) module.applyInbox(c,inbox,objectMap(inbox,"payload"));
        }
    }
    private static boolean canPostToday(SimContext c,String bookId) {
        String today=c.now().atZone(BOOK_ZONE).toLocalDate().toString();
        for(Map<String,Object> day:c.all("businessDays")) if(Objects.equals(bookId,s(day,"bookId")) && today.equals(s(day,"businessDate"))) return "OPEN".equals(s(day,"status"));
        return true;
    }
    private Object callbackException(SimContext c,Map<String,Object> inbox,String reason,Map<String,Object> target) {
        inbox.put("status","EXCEPTION");inbox.put("exceptionCode",reason);c.bump(inbox);difference(c,inbox,target,reason);
        c.audit("MOCK_EVENT_EXCEPTION","inbox",s(inbox,"id"),map("reason",reason));return map("accepted",true,"resourceId",s(inbox,"id"));
    }
    private static void difference(SimContext c,Map<String,Object> inbox,Map<String,Object> target,String reason) {
        String type=differenceType(reason), severity="SCOPE".equals(type)?"S0":"S1";
        if("S0".equals(severity)) c.state.meta.put("emergencyStopped",true);
        c.create("differences",map("reconciliationId",null,"inboxId",s(inbox,"id"),"merchantUid",s(inbox,"merchantUid"),"bookId",target==null?null:s(target,"bookId"),
            "reference",s(inbox,"reference"),"repaymentId",target!=null && target.containsKey("receivedMinor")?s(target,"id"):null,
            "differenceType",type,"type",type,"severity",severity,"blocking",true,"status","OPEN","dueAt",c.now().plusSeconds("S0".equals(severity)?7200:86400).toString(),"ackDueAt",c.now().plusSeconds("S0".equals(severity)?900:14400).toString(),"slaPolicy","CANDIDATE_ELAPSED_TIME_FIXTURE_PENDING_WORKING_CALENDAR_APPROVAL","expectedMinor",target==null?0L:n(target,"amountMinor"),"actualMinor",n(inbox,"amountMinor"),
            "creatorId",null,"reason",reason,"environment","SIMULATION"));
    }
    private static String differenceType(String reason) {
        if("UNKNOWN_REFERENCE".equals(reason)) return "MISSING_LOCAL";
        if("AMOUNT_MISMATCH".equals(reason)) return "AMOUNT";
        if("LATE_SUCCESS".equals(reason)) return "LATE";
        return "SCOPE";
    }
    public static Map<String,Object> canonicalMockResult(Map<String,Object> b) {
        return map("providerEventId",s(b,"providerEventId"),"reference",s(b,"reference"),"eventType",s(b,"eventType"),"amountMinor",n(b,"amountMinor"),
            "merchantUid",s(b,"merchantUid"),"currency",s(b,"currency"),"occurredAt",s(b,"occurredAt"),"environment",s(b,"environment"));
    }
    /** Supplemental test control: explicit simulator failures, never a timeout interpreted as failure. */
    public static Map<String,Object> simulateFailure(SimContext c,Map<String,Object> b) {
        String reference=s(b,"reference"),result=s(b,"result"),eventId=s(b,"eventId");
        c.check(eventId!=null && !eventId.trim().isEmpty() && s(b,"reason")!=null && !s(b,"reason").trim().isEmpty(),422,"VALIDATION_ERROR","需提供事件编号与明确原因");
        c.check(Arrays.asList("FAILED","CANCELLED","TIMEOUT").contains(result),422,"VALIDATION_ERROR","仅支持明确失败、取消或结果未知");
        Map<String,Object> target=find(c,"repayments","reference",reference);boolean refund=false;
        if(target==null) {target=find(c,"refunds","reference",reference);refund=true;}
        c.check(target!=null,404,"UNKNOWN_REFERENCE","模拟业务号不存在");
        c.requireScope(s(target,"merchantUid"),null,s(target,"bookId"),"SIM_CONTROLLER");
        String hash=c.hashObject(map("reference",reference,"version",n(b,"version"),"result",result,"reason",s(b,"reason"),"eventId",eventId));
        for(Map<String,Object> event:c.all("simulationResults")) if(eventId.equals(s(event,"eventId"))) {
            c.check(hash.equals(s(event,"payloadHash")),409,"EVENT_CONTENT_CONFLICT","同一模拟事件编号不得改变内容");return map("accepted",true,"resourceId",s(event,"id"));
        }
        c.version(target,b);
        for(Map<String,Object> fact:c.all("providerResults"))c.check(!reference.equals(s(fact,"reference")),409,"PROVIDER_ALREADY_SUCCEEDED","已存在模拟渠道成功事实，请查单补核，不能改记失败");
        c.check(refund?"PROCESSING".equals(s(target,"status")):"PENDING".equals(s(target,"status")),409,"STATE_CONFLICT","仅处理中模拟单可接收故障结果");
        c.check(refund?n(target,"returnedMinor")==0:n(target,"receivedMinor")==0,409,"ALREADY_POSTED","已有成功入账，不能改记失败");
        Map<String,Object> event=c.create("simulationResults",map("eventId",eventId,"payloadHash",hash,"reference",reference,"result",result,"reason",s(b,"reason"),"creatorId",c.actorId(),"environment","SIMULATION"));
        if("TIMEOUT".equals(result)) {target.put("channelOutcome","UNKNOWN");target.put("failureConfirmed",false);}
        else {target.put("channelOutcome",result);target.put("failureConfirmed",true);target.put("status",refund?"FAILED":"CANCELLED".equals(result)?"CLOSED":"FAILED");}
        target.put("lastSimulatorEventId",eventId);c.bump(target);
        c.audit("SIMULATOR_RESULT","simulationResults",s(event,"id"),map("reference",reference,"result",result));
        c.notify(s(target,"userId"),"模拟渠道结果已更新",refund?"refunds":"repayments",s(target,"id"));return map("accepted",true,"resourceId",s(event,"id"));
    }
    private static void applyReceipt(SimContext c,Map<String,Object> r,long amount,String eventKey,String occurredAt,boolean hold) {
        c.check(n(r,"receivedMinor")==0,409,"RECEIPT_ALREADY_POSTED","模拟收款已经入账");
        List<Map<String,Object>> lines=new ArrayList<Map<String,Object>>();lines.add(line("SIM_RECEIPT","DR",amount,null,null));
        long allocated=hold?0:allocate(c,r,amount,lines);long unallocated=amount-allocated;
        if(unallocated>0) lines.add(line("SIM_RETURN_PAYABLE","CR",unallocated,null,null));
        Map<String,Object> journal=postJournal(c,s(r,"merchantUid"),s(r,"userUid"),unallocated>0?"J-03":"J-02","repayments",s(r,"id"),eventKey,occurredAt,lines);
        r.put("receivedMinor",amount);r.put("allocatedMinor",allocated);r.put("unallocatedMinor",unallocated);r.put("status",hold?"EXCEPTION":"CONFIRMED");r.put("receiptJournalId",s(journal,"id"));
        r.put("confirmedAt",c.now().toString());c.bump(r);refreshAccount(c,s(r,"userUid"));
        c.notify(s(r,"userId"),hold?"迟到模拟款已保留待复核":"模拟还款已核实","repayments",s(r,"id"));
    }
    /** J-07 is a reclassification of a verified receipt; it never debits SIM_RECEIPT again. */
    public static Map<String,Object> applyHeldRepayment(SimContext c,Map<String,Object> r,String reviewId) {
        c.requireScope(s(r,"merchantUid"),null,s(r,"bookId"),"LEDGER_CHECKER");c.independent(s(r,"creatorId"));
        c.check("EXCEPTION".equals(s(r,"status")) && n(r,"unallocatedMinor")>0,409,"NO_HELD_RECEIPT","没有待复核款项");
        long held=n(r,"unallocatedMinor");List<Map<String,Object>> lines=new ArrayList<Map<String,Object>>();long allocated=allocate(c,r,held,lines);
        if(allocated>0) {lines.add(0,line("SIM_RETURN_PAYABLE","DR",allocated,null,null));postJournal(c,s(r,"merchantUid"),s(r,"userUid"),"J-07","repayments",s(r,"id"),"reallocate:"+reviewId,c.now().toString(),lines);}
        r.put("allocatedMinor",Math.addExact(n(r,"allocatedMinor"),allocated));r.put("unallocatedMinor",held-allocated);r.put("status","CONFIRMED");r.put("reviewId",reviewId);c.bump(r);refreshAccount(c,s(r,"userUid"));
        c.notify(s(r,"userId"),"待分配模拟款已复核","repayments",s(r,"id"));return repaymentDto(c,r);
    }
    private static long allocate(SimContext c,Map<String,Object> repayment,long available,List<Map<String,Object>> lines) {
        List<Map<String,Object>> rs=selectedReceivables(c,values(repayment,"receivableIds"),s(repayment,"userUid"),s(repayment,"merchantUid"));
        long remaining=available; List<Map<String,Object>> allocations=new ArrayList<Map<String,Object>>(CommerceModule.<Map<String,Object>>values(repayment,"allocations"));
        for(Map<String,Object> r:rs) {
            long portion=Math.min(remaining,n(r,"outstandingMinor"));if(portion<=0)continue;
            reduce(c,r,portion);lines.add(line("SIM_AR","CR",portion,s(r,"id"),s(r,"orderId")));
            allocations.add(map("receivableId",s(r,"id"),"amountMinor",portion,"allocatedAt",c.now().toString()));remaining-=portion;if(remaining==0)break;
        }
        repayment.put("allocations",allocations);return available-remaining;
    }
    private Object refundCreate(SimContext c,Map<String,Object> b) {
        Map<String,Object> o=c.get("orders",s(b,"orderId"));requireRead(c,o,"OWNER");state(c,o,"FULFILLED");evidence(c,b,false);
        long amount=positive(c,b,"requestedMinor",100000000L), pending=0;
        for(Map<String,Object> f:c.all("refunds")) if(s(o,"id").equals(s(f,"orderId")) && "REQUESTED".equals(s(f,"status"))) pending=Math.addExact(pending,n(f,"requestedMinor"));
        c.check(amount<=n(o,"totalMinor")-n(o,"refundedMinor")-n(o,"adjustedMinor")-pending,409,"REFUND_LIMIT_EXCEEDED","累计退货申请超出原可退金额");
        Map<String,Object> r=c.create("refunds",map("orderId",s(o,"id"),"merchantUid",s(o,"merchantUid"),"storeId",s(o,"storeId"),"bookId",s(o,"bookId"),"userUid",s(o,"userUid"),"userId",s(o,"userId"),
            "creatorId",c.actorId(),"requestedMinor",amount,"principalReductionMinor",0L,"returnPayableMinor",0L,"returnedMinor",0L,"status","REQUESTED","reason",s(b,"reason"),
            "evidenceIds",new ArrayList<Object>(values(b,"evidenceIds")),"environment","SIMULATION","attempt",0L));r.put("reference","sim-refund-"+s(r,"id"));
        c.audit("REFUND_REQUESTED","refunds",s(r,"id"),map("requestedMinor",amount));return refundDto(c,r);
    }
    private Object refundReview(SimContext c,Map<String,Object> f,Map<String,Object> b) {
        c.requireScope(s(f,"merchantUid"),s(f,"storeId"),s(f,"bookId"),"LEDGER_CHECKER");c.independent(s(f,"creatorId"));c.version(f,b);state(c,f,"REQUESTED");evidence(c,b,false);
        if(!approve(b)) f.put("status","REJECTED");
        else {
            Map<String,Object> o=c.get("orders",s(f,"orderId")),r=c.get("receivables",s(o,"receivableId"));long amount=n(f,"requestedMinor");
            c.check(amount<=n(o,"totalMinor")-n(o,"refundedMinor")-n(o,"adjustedMinor"),409,"REFUND_LIMIT_EXCEEDED","可退金额已变化");
            long reduction=Math.min(amount,n(r,"outstandingMinor")),payable=amount-reduction; List<Map<String,Object>> lines=new ArrayList<Map<String,Object>>();
            lines.add(line("SIM_REFUND_CTRL","DR",amount,s(r,"id"),s(o,"id")));
            if(reduction>0) lines.add(line("SIM_AR","CR",reduction,s(r,"id"),s(o,"id")));
            if(payable>0) lines.add(line("SIM_RETURN_PAYABLE","CR",payable,s(r,"id"),s(o,"id")));
            postJournal(c,s(f,"merchantUid"),s(f,"userUid"),"J-04","refunds",s(f,"id"),"refund-approve:"+s(f,"id"),c.now().toString(),lines);
            if(reduction>0) reduce(c,r,reduction);o.put("refundedMinor",Math.addExact(n(o,"refundedMinor"),amount));c.bump(o);
            f.put("principalReductionMinor",reduction);f.put("returnPayableMinor",payable);f.put("status",payable>0?"PROCESSING":"SUCCEEDED");f.put("approvedAt",c.now().toString());
            if(payable>0) submitRefund(c,f);refreshAccount(c,s(f,"userUid"));
        }
        f.put("checkerId",c.actorId());f.put("decisionReason",s(b,"reason"));f.put("decisionEvidenceIds",new ArrayList<Object>(values(b,"evidenceIds")));c.bump(f);
        c.notify(s(f,"userId"),"模拟退款审批已更新","refunds",s(f,"id"));c.audit("REFUND_REVIEWED","refunds",s(f,"id"),map("decision",s(b,"decision")));return refundDto(c,f);
    }
    private Object refundRetry(SimContext c,Map<String,Object> f,Map<String,Object> b) {
        c.requireScope(s(f,"merchantUid"),s(f,"storeId"),s(f,"bookId"),"LEDGER_CHECKER");c.independent(s(f,"creatorId"));c.version(f,b);
        c.check(Arrays.asList("FAILED","EXCEPTION").contains(s(f,"status")),409,"REFUND_NOT_RETRYABLE","仅明确失败或异常退款可重试");
        c.check(n(f,"returnedMinor")==0 && n(f,"returnPayableMinor")>0,409,"REFUND_ALREADY_RETURNED","退款已完成或没有待退金额");
        for(Map<String,Object> inbox:c.all("inbox")) c.check(!(s(f,"reference").equals(s(inbox,"reference")) && "REFUND_CONFIRMED".equals(s(inbox,"eventType")) && "APPLIED".equals(s(inbox,"status"))),409,"REFUND_ALREADY_RETURNED","已查询到成功结果，禁止重复退回");
        // A transport timeout is not evidence of failure. The simulator must record an explicit result first.
        c.check(bool(f,"failureConfirmed"),409,"REFUND_RESULT_UNKNOWN","原模拟渠道结果仍未知，须先核查，不能据超时重试");
        f.put("status","PROCESSING");f.put("failureConfirmed",false);f.put("retryCheckerId",c.actorId());submitRefund(c,f);c.bump(f);c.audit("REFUND_RETRIED","refunds",s(f,"id"),map("attempt",n(f,"attempt")));return refundDto(c,f);
    }
    private static void submitRefund(SimContext c,Map<String,Object> f) {f.put("attempt",n(f,"attempt")+1);c.create("outbox",map("eventKey","refund:"+s(f,"id")+":"+n(f,"attempt"),"reference",s(f,"reference"),"type","MOCK_REFUND_INTENT","status","PENDING","attempts",0L,"refundId",s(f,"id"),"environment","SIMULATION"));}
    private Object disputeCreate(SimContext c,Map<String,Object> b) {
        Map<String,Object> o=c.get("orders",s(b,"orderId"));requireOwner(c,o);state(c,o,"FULFILLED");evidence(c,b,false);
        for(Map<String,Object> d:c.all("disputes")) c.check(!(s(o,"id").equals(s(d,"orderId")) && !"CLOSED".equals(s(d,"status"))),409,"DISPUTE_ALREADY_OPEN","该订单已有争议，可查看或申诉");
        Map<String,Object> d=c.create("disputes",map("orderId",s(o,"id"),"merchantUid",s(o,"merchantUid"),"storeId",s(o,"storeId"),"bookId",s(o,"bookId"),"userUid",s(o,"userUid"),"userId",s(o,"userId"),
            "creatorId",c.actorId(),"status","OPEN","reason",s(b,"reason"),"evidenceIds",new ArrayList<Object>(values(b,"evidenceIds")),"history",new ArrayList<Map<String,Object>>()));
        updateDisputeFlags(c,o);c.audit("DISPUTE_OPENED","disputes",s(d,"id"),map("orderId",s(o,"id")));return disputeDto(c,d);
    }
    private Object disputeResolve(SimContext c,Map<String,Object> d,Map<String,Object> b) {
        c.requireScope(s(d,"merchantUid"),s(d,"storeId"),s(d,"bookId"),"SUPPORT");c.independent(s(d,"creatorId"));c.version(d,b);evidence(c,b,false);
        c.check(Arrays.asList("OPEN","REVIEWING","APPEALED").contains(s(d,"status")),409,"DISPUTE_STATE_CONFLICT","争议不可重复结案");
        if("APPEALED".equals(s(d,"status"))) c.independent(s(d,"resolverId"));
        appendHistory(c,d,"REVIEWING",s(b,"resolution"),b);d.put("status","REVIEWING");
        d.put("resolverId",c.actorId());d.put("resolution",s(b,"resolution"));d.put("status","RESOLVED");d.put("resolvedAt",c.now().toString());
        // Seven days is a labelled isolated test fixture, not an approved operational appeal policy.
        long appealSeconds=c.state.meta.containsKey("fixtureDisputeAppealSeconds")?n(c.state.meta,"fixtureDisputeAppealSeconds"):604800L;
        d.put("appealUntil",c.now().plusSeconds(appealSeconds).toString());d.put("appealWindowSource","ISOLATED_SIMULATION_FIXTURE_PENDING_FORMAL_PARAMETERS");
        appendHistory(c,d,"RESOLVED",s(b,"resolution"),b);c.bump(d);updateDisputeFlags(c,c.get("orders",s(d,"orderId")));
        c.notify(s(d,"userId"),"模拟争议已有结论，可在测试申诉窗口内申请复核","disputes",s(d,"id"));return disputeDto(c,d);
    }
    private Object disputeAppeal(SimContext c,Map<String,Object> d,Map<String,Object> b) {
        requireOwner(c,d);c.version(d,b);state(c,d,"RESOLVED");evidence(c,b,false);
        c.check(c.now().isBefore(Instant.parse(s(d,"appealUntil"))),409,"APPEAL_WINDOW_EXPIRED","测试申诉窗口已结束");
        d.put("status","APPEALED");d.put("appealReason",s(b,"resolution"));appendHistory(c,d,"APPEALED",s(b,"resolution"),b);c.bump(d);updateDisputeFlags(c,c.get("orders",s(d,"orderId")));
        c.audit("DISPUTE_APPEALED","disputes",s(d,"id"),map("previousResolverId",s(d,"resolverId")));return disputeDto(c,d);
    }
    public static void expireDisputes(SimContext c) {
        for(Map<String,Object> d:c.all("disputes")) if("RESOLVED".equals(s(d,"status")) && d.get("appealUntil")!=null && !c.now().isBefore(Instant.parse(s(d,"appealUntil")))) {
            d.put("status","CLOSED");c.bump(d);updateDisputeFlags(c,c.get("orders",s(d,"orderId")));c.notify(s(d,"userId"),"模拟争议测试申诉窗口已结束","disputes",s(d,"id"));
        }
    }
    private static void updateDisputeFlags(SimContext c,Map<String,Object> o) {
        boolean open=false;for(Map<String,Object> d:c.all("disputes")) if(s(o,"id").equals(s(d,"orderId")) && Arrays.asList("OPEN","REVIEWING","APPEALED").contains(s(d,"status")))open=true;
        Map<String,Object> r=c.get("receivables",s(o,"receivableId"));if(open!=bool(r,"hasOpenDispute")){r.put("hasOpenDispute",open);c.bump(r);}refreshAccount(c,s(o,"userUid"));
    }
    private static void appendHistory(SimContext c,Map<String,Object> d,String status,String reason,Map<String,Object> b) {
        List<Map<String,Object>> history=new ArrayList<Map<String,Object>>(CommerceModule.<Map<String,Object>>values(d,"history"));history.add(map("status",status,"actorId",c.actorId(),"reason",reason,"evidenceIds",new ArrayList<Object>(values(b,"evidenceIds")),"at",c.now().toString()));d.put("history",history);
        c.audit("DISPUTE_"+status,"disputes",s(d,"id"),map("reason",reason));
    }
    private Object adjustmentCreate(SimContext c,Map<String,Object> b) {
        Map<String,Object> r=c.get("receivables",s(b,"receivableId"));c.requireScope(s(r,"merchantUid"),s(r,"storeId"),s(r,"bookId"),"LEDGER_MAKER");evidence(c,b,false);
        long amount=positive(c,b,"amountMinor",100000000L);String kind=s(b,"kind");c.check(Arrays.asList("DOWNWARD","DUPLICATE_REVERSAL").contains(kind),422,"ADJUSTMENT_KIND_FORBIDDEN","仅允许下调或重复事件更正");
        Map<String,Object> original=c.get("journals",s(b,"originalJournalId"));c.check(s(r,"merchantUid").equals(s(original,"merchantUid")),403,"SCOPE_FORBIDDEN","原分录不属于应收商家");
        long originalDebit=0;for(Map<String,Object> l:c.all("lines")) if(s(original,"id").equals(s(l,"journalId")) && s(r,"id").equals(s(l,"receivableId")) && "SIM_AR".equals(s(l,"accountCode")) && "DR".equals(s(l,"side"))) originalDebit=Math.addExact(originalDebit,n(l,"amountMinor"));
        c.check(originalDebit>0 && amount<=originalDebit && amount<=n(r,"outstandingMinor"),409,"ADJUSTMENT_EXCEEDS_PRINCIPAL","更正必须关联原应收事件且不得产生负本金");
        long adjusted=0;for(Map<String,Object> a:c.all("adjustments"))if(s(original,"id").equals(s(a,"originalJournalId")) && "POSTED".equals(s(a,"status")))adjusted+=n(a,"amountMinor");
        c.check(amount<=originalDebit-adjusted,409,"ADJUSTMENT_EXCEEDS_ORIGINAL","原事件可更正金额不足");
        Map<String,Object> a=c.create("adjustments",map("originalJournalId",s(original,"id"),"receivableId",s(r,"id"),"orderId",s(r,"orderId"),"merchantUid",s(r,"merchantUid"),"storeId",s(r,"storeId"),"bookId",s(r,"bookId"),"userUid",s(r,"userUid"),"userId",s(r,"userId"),
            "creatorId",c.actorId(),"status","REQUESTED","kind",kind,"amountMinor",amount,"reason",s(b,"reason"),"evidenceIds",new ArrayList<Object>(values(b,"evidenceIds")),"originalDebitMinor",originalDebit,
            "impact",map("outstandingBeforeMinor",n(r,"outstandingMinor"),"outstandingAfterMinor",n(r,"outstandingMinor")-amount,"quotaReleaseMinor",amount,"createsNewDebt",false)));
        c.audit("ADJUSTMENT_REQUESTED","adjustments",s(a,"id"),objectMap(a,"impact"));return adjustmentDto(c,a);
    }
    private Object adjustmentReview(SimContext c,Map<String,Object> a,Map<String,Object> b) {
        c.requireScope(s(a,"merchantUid"),s(a,"storeId"),s(a,"bookId"),"LEDGER_CHECKER");c.independent(s(a,"creatorId"));c.version(a,b);state(c,a,"REQUESTED");evidence(c,b,false);
        a.put("status",approve(b)?"APPROVED":"REJECTED");a.put("checkerId",c.actorId());a.put("decisionReason",s(b,"reason"));a.put("decisionEvidenceIds",new ArrayList<Object>(values(b,"evidenceIds")));c.bump(a);
        c.audit("ADJUSTMENT_REVIEWED","adjustments",s(a,"id"),map("decision",s(b,"decision")));return adjustmentDto(c,a);
    }
    private Object adjustmentPost(SimContext c,Map<String,Object> a,Map<String,Object> b) {
        c.requireScope(s(a,"merchantUid"),s(a,"storeId"),s(a,"bookId"),"LEDGER_CHECKER");c.independent(s(a,"creatorId"));
        if("POSTED".equals(s(a,"status"))) return adjustmentDto(c,a);c.version(a,b);state(c,a,"APPROVED");c.check(s(a,"checkerId")!=null && !s(a,"creatorId").equals(s(a,"checkerId")),409,"INVALID_APPROVAL","缺少独立复核证据");
        Map<String,Object> r=c.get("receivables",s(a,"receivableId")),o=c.get("orders",s(a,"orderId"));long amount=n(a,"amountMinor"),already=0;
        for(Map<String,Object> other:c.all("adjustments"))if(s(a,"originalJournalId").equals(s(other,"originalJournalId")) && "POSTED".equals(s(other,"status")))already+=n(other,"amountMinor");
        c.check(amount<=n(r,"outstandingMinor") && amount<=n(a,"originalDebitMinor")-already && amount<=n(o,"totalMinor")-n(o,"refundedMinor")-n(o,"adjustedMinor"),409,"ADJUSTMENT_EXCEEDS_PRINCIPAL","原交易已变化，须重新评估影响");
        Map<String,Object> journal=postJournal(c,s(a,"merchantUid"),s(a,"userUid"),"J-06","adjustments",s(a,"id"),"adjustment:"+s(a,"id"),c.now().toString(),
            Arrays.asList(line("SIM_REFUND_CTRL","DR",amount,s(r,"id"),s(o,"id")),line("SIM_AR","CR",amount,s(r,"id"),s(o,"id"))));
        reduce(c,r,amount);o.put("adjustedMinor",n(o,"adjustedMinor")+amount);c.bump(o);a.put("status","POSTED");a.put("postedJournalId",s(journal,"id"));a.put("postedAt",c.now().toString());c.bump(a);refreshAccount(c,s(a,"userUid"));
        c.notify(s(a,"userId"),"模拟应收已按批准更正调减","adjustments",s(a,"id"));c.audit("ADJUSTMENT_POSTED","adjustments",s(a,"id"),map("journalId",s(journal,"id"),"originalJournalId",s(a,"originalJournalId")));return adjustmentDto(c,a);
    }
    private Object ruleCreate(SimContext c,Map<String,Object> b) {
        c.requireRoles("LEDGER_MAKER");String code=s(b,"code");c.check(code!=null && code.startsWith("RV-SIM-"),422,"SIMULATION_ONLY","模拟规则编号必须以RV-SIM-开头");
        for(Map<String,Object> r:c.all("rules"))c.check(!code.equals(s(r,"code")),409,"RULE_CODE_EXISTS","规则编号已存在");
        positive(c,b,"quotaMinor",1000000L);positive(c,b,"transactionMaxMinor",1000000L);positive(c,b,"termDays",365L);
        c.check(n(b,"transactionMaxMinor")<=n(b,"quotaMinor"),422,"INVALID_RULE","单笔上限不得超过总额度");
        c.check(n(b,"credentialTtlSeconds")>=30 && n(b,"credentialTtlSeconds")<=600 && n(b,"reservationTtlSeconds")>=60 && n(b,"reservationTtlSeconds")<=3600,422,"INVALID_RULE","凭证或预占有效期超范围");
        Map<String,Object> data=new LinkedHashMap<String,Object>(b);data.put("status","DRAFT");data.put("creatorId",c.actorId());data.put("environment","SIMULATION");data.put("parameterStatus","ISOLATED_TEST_FIXTURE_PENDING_FORMAL_APPROVAL");
        Map<String,Object> r=c.create("rules",data);c.audit("RULE_CREATED","rules",s(r,"id"),map("code",code,"parameterStatus",s(r,"parameterStatus")));return ruleDto(c,r);
    }
    private Object ruleReview(SimContext c,Map<String,Object> r,Map<String,Object> b) {
        c.requireRoles("LEDGER_CHECKER");c.independent(s(r,"creatorId"));c.version(r,b);state(c,r,"DRAFT");evidence(c,b,false);
        if(approve(b)) {
            for(Map<String,Object> a:c.all("accounts"))c.check(n(a,"reservedMinor")+n(a,"principalMinor")<=n(r,"quotaMinor"),409,"RULE_QUOTA_BELOW_EXPOSURE","新额度低于现有模拟占用");
            Object oldId=c.state.meta.get("ruleId");if(oldId!=null) {Map<String,Object> old=c.get("rules",str(oldId));old.put("status","RETIRED");c.bump(old);}
            r.put("status","APPROVED");c.state.meta.put("ruleId",s(r,"id"));
            for(Map<String,Object> a:c.all("accounts")) {a.put("totalMinor",n(r,"quotaMinor"));c.bump(a);refreshAccount(c,s(a,"userUid"));}
        } else r.put("status","RETIRED");
        r.put("checkerId",c.actorId());r.put("decisionReason",s(b,"reason"));r.put("decisionEvidenceIds",new ArrayList<Object>(values(b,"evidenceIds")));c.bump(r);
        c.audit("RULE_REVIEWED","rules",s(r,"id"),map("decision",s(b,"decision"),"scope","SIMULATION_FIXTURE_ONLY"));return ruleDto(c,r);
    }
    public static String bookFor(SimContext c,String merchantUid) {
        for(Map<String,Object> b:c.all("books")) if(merchantUid.equals(s(b,"merchantUid"))) return s(b,"id");
        Map<String,Object> book=c.create("books",map("merchantUid",merchantUid,"currency","CNY","timezone","Asia/Shanghai","environment","SIMULATION","status","ACTIVE"));return s(book,"id");
    }
    public static Map<String,Object> line(String accountCode,String side,long amountMinor,String receivableId,String orderId) {
        Map<String,Object> l=map("accountCode",accountCode,"side",side,"amountMinor",amountMinor,"currency","CNY");if(receivableId!=null)l.put("receivableId",receivableId);if(orderId!=null)l.put("orderId",orderId);return l;
    }
    /** Only the current Asia/Shanghai OPEN day is writable. Historical and future dates are never backfilled. */
    public static Map<String,Object> postJournal(SimContext c,String merchantUid,String userUid,String templateCode,String sourceType,String sourceId,String eventKey,String occurredAt,List<Map<String,Object>> entries) {
        String fingerprint=c.hashObject(map("merchantUid",merchantUid,"userUid",userUid,"templateCode",templateCode,"sourceType",sourceType,"sourceId",sourceId,"occurredAt",occurredAt,"lines",entries));
        for(Map<String,Object> j:c.all("journals")) if(eventKey.equals(s(j,"eventKey"))) {c.check(fingerprint.equals(s(j,"fingerprint")),409,"JOURNAL_EVENT_CONFLICT","入账事件内容已变化");return j;}
        c.check(Arrays.asList("J-01","J-02","J-03","J-04","J-05","J-06","J-07").contains(templateCode),422,"JOURNAL_TEMPLATE_INVALID","无效模拟分录模板");
        long debit=0,credit=0;for(Map<String,Object> l:entries) {
            long amount=positive(c,l,"amountMinor",Long.MAX_VALUE);c.check(Arrays.asList("SIM_AR","SIM_RECEIPT","SIM_SALES_CTRL","SIM_REFUND_CTRL","SIM_RETURN_PAYABLE").contains(s(l,"accountCode")),422,"ACCOUNT_CODE_INVALID","无效模拟科目");
            c.check("CNY".equals(s(l,"currency")),422,"CURRENCY_MISMATCH","模拟分录币种不一致");
            if("DR".equals(s(l,"side")))debit=Math.addExact(debit,amount);else {c.check("CR".equals(s(l,"side")),422,"JOURNAL_SIDE_INVALID","借贷方向无效");credit=Math.addExact(credit,amount);}
        }
        c.check(debit>0 && debit==credit,409,"JOURNAL_UNBALANCED","模拟分录借贷不平衡");String bookId=bookFor(c,merchantUid);LocalDate postingDate=c.now().atZone(BOOK_ZONE).toLocalDate();Map<String,Object> day=null;
        for(Map<String,Object> d:c.all("businessDays"))if(bookId.equals(s(d,"bookId")) && postingDate.toString().equals(s(d,"businessDate"))){day=d;break;}
        if(day==null) day=c.create("businessDays",map("bookId",bookId,"merchantUid",merchantUid,"businessDate",postingDate.toString(),"status","OPEN","reportRevision",1L,
            "cutoffAt",postingDate.plusDays(1).atStartOfDay(BOOK_ZONE).toInstant().toString(),"highWatermark","0","snapshotHash",null,"closedAt",null,"environment","SIMULATION"));
        c.check("OPEN".equals(s(day,"status")),409,"CURRENT_BUSINESS_DAY_NOT_OPEN","当前模拟业务日不可写，等待恢复或下一实际业务日");
        Instant occurred=parseInstant(c,occurredAt);c.check(!occurred.isAfter(c.now()),422,"FUTURE_EVENT","不得倒签或记入未来事件");Map<String,Object> j=c.create("journals",map("bookId",bookId,"merchantUid",merchantUid,"userUid",userUid,"templateCode",templateCode,"sourceType",sourceType,"sourceId",sourceId,
            "eventKey",eventKey,"fingerprint",fingerprint,"occurredAt",occurred.toString(),"occurredDate",occurred.atZone(BOOK_ZONE).toLocalDate().toString(),"postingDate",s(day,"businessDate"),"currency","CNY","environment","SIMULATION","debitMinor",debit,"creditMinor",credit,"debitTotalMinor",debit,"creditTotalMinor",credit,"businessDayId",s(day,"id"),"occurredBusinessDate",occurred.atZone(BOOK_ZONE).toLocalDate().toString()));
        j.put("sequenceNo",n(j,"id"));
        for(Map<String,Object> source:entries) {Map<String,Object> l=new LinkedHashMap<String,Object>(source);l.put("journalId",s(j,"id"));l.put("bookId",bookId);l.put("merchantUid",merchantUid);l.put("userUid",userUid);l.put("postingDate",s(day,"businessDate"));l.put("environment","SIMULATION");c.create("lines",l);}
        day.put("highWatermark",s(j,"id"));c.bump(day);c.audit("JOURNAL_POSTED","journals",s(j,"id"),map("templateCode",templateCode,"eventKey",eventKey,"postingDate",s(day,"businessDate")));return j;
    }
    private static List<Map<String,Object>> selectedReceivables(SimContext c,List<Object> ids,String uid,String merchant) {
        c.check(ids!=null && !ids.isEmpty() && ids.size()<=50,422,"RECEIVABLES_REQUIRED","需选择1至50笔本人同商家应收");
        List<Map<String,Object>> selected=new ArrayList<Map<String,Object>>();Set<String> unique=new HashSet<String>();
        for(Object id:ids) {c.check(unique.add(str(id)),422,"DUPLICATE_RECEIVABLE","应收编号重复");Map<String,Object> r=c.get("receivables",str(id));
            c.check(uid.equals(s(r,"userUid")) && merchant.equals(s(r,"merchantUid")),403,"RECEIVABLE_SCOPE_MISMATCH","只可合并本人同一收款商家的应收，请分开操作");selected.add(r);}
        Collections.sort(selected,new Comparator<Map<String,Object>>() {public int compare(Map<String,Object> a,Map<String,Object> b) {int due=Instant.parse(s(a,"dueAt")).compareTo(Instant.parse(s(b,"dueAt")));return due==0?Long.compare(n(a,"id"),n(b,"id")):due;}});return selected;
    }
    private static void reduce(SimContext c,Map<String,Object> r,long amount) {long remainder=n(r,"outstandingMinor")-amount;c.check(amount>0 && remainder>=0,409,"NEGATIVE_PRINCIPAL","不得生成负应收");r.put("outstandingMinor",remainder);r.put("status",remainder==0?"SETTLED":"PARTIAL");c.bump(r);}
    private static long positive(SimContext c,Map<String,Object> b,String key,long max) {Object value=b.get(key);long n;try {n=new BigDecimal(String.valueOf(value)).longValueExact();}catch(Exception e){throw new SimException(422,"VALIDATION_ERROR",key+"必须为整数分");}c.check(n>0 && n<=max,422,"VALIDATION_ERROR",key+"超出有效范围");return n;}
    private static Instant parseInstant(SimContext c,String value) {try{return Instant.parse(value);}catch(Exception e){throw new SimException(422,"VALIDATION_ERROR","日期时间格式无效");}}
    private static boolean approve(Map<String,Object> b) {return "APPROVE".equals(s(b,"decision"));}
    private static void state(SimContext c,Map<String,Object> r,String expected) {c.check(expected.equals(s(r,"status")),409,"STATE_CONFLICT","对象当前状态不允许该操作");}
    private static Map<String,Object> find(SimContext c,String kind,String key,String value) {for(Map<String,Object> r:c.all(kind))if(Objects.equals(value,s(r,key)))return r;return null;}
    private static String userId(SimContext c,String uid) {Map<String,Object> user=find(c,"users","uid",uid);c.check(user!=null,404,"NOT_FOUND","模拟用户不存在");return s(user,"id");}
    private static void evidence(SimContext c,Map<String,Object> b,boolean required) {
        List<Object> ids=values(b,"evidenceIds");c.check(!required || !ids.isEmpty(),422,"EVIDENCE_REQUIRED","模拟线下登记需要合成凭证");
        for(Object id:ids) {Map<String,Object> f=c.get("files",str(id));c.check("READY".equals(s(f,"status")) && "SIM_EVIDENCE".equals(s(f,"purpose")),409,"EVIDENCE_NOT_READY","凭证尚不可用或用途不符");
            c.check(c.actorId().equals(s(f,"ownerId")) || c.actorId().equals(s(f,"userId")) || c.actorId().equals(s(f,"uploaderId")) || (f.get("merchantUid")!=null && c.inScope(s(f,"merchantUid"),null,s(f,"bookId"),"OWNER","LEDGER_CHECKER","SUPPORT")),403,"EVIDENCE_SCOPE_FORBIDDEN","凭证不属于当前授权范围");}
    }
    public static Map<String,Object> repaymentDto(SimContext c,Map<String,Object> r) {return c.project(r,"id","reference","merchantUid","amountMinor","receivedMinor","unallocatedMinor","status","version");}
    private static Map<String,Object> offlineDto(SimContext c,Map<String,Object> r) {return c.project(r,"id","repaymentId","status","version");}
    private static Map<String,Object> refundDto(SimContext c,Map<String,Object> r) {return c.project(r,"id","orderId","reference","requestedMinor","principalReductionMinor","returnPayableMinor","status","version");}
    private static Map<String,Object> disputeDto(SimContext c,Map<String,Object> r) {return c.project(r,"id","orderId","status","reason","resolution","version");}
    private static Map<String,Object> adjustmentDto(SimContext c,Map<String,Object> r) {return c.project(r,"id","status","amountMinor","version");}
    public static Map<String,Object> ruleDto(SimContext c,Map<String,Object> r) {return c.project(r,"id","code","status","quotaMinor","transactionMaxMinor","termDays","credentialTtlSeconds","reservationTtlSeconds","version");}
}
