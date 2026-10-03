package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Standalone domain regression runner; also invoked by CommerceFinanceJUnitTest. */
public final class CommerceFinanceInvariantTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        lifecycleAndDuplicateEvents(); unknownAndMismatchedCallbacks(); quotaExpiryAndCancellation(); snapshotsAndScope(); repaymentAllocation();
        offlineIndependentReview(); lateSuccessAndReallocation(); refundFailureAndRetry(); disputesFreezeOnlyNewCredit(); adjustmentsAreIndependentAndAppendOnly(); ruleVersionsKeepSnapshots(); closedBusinessDayNeverBackfilled(); lostNotificationRecoveredByQuery();
        System.out.println("Commerce/finance regression scenarios=13 assertions="+assertions+" PASS");
    }
    private static void lifecycleAndDuplicateEvents() throws Exception {
        F f=new F();String order=f.fulfilled("s0000001",2000);Map<String,Object> r=f.receivable(order);String payment=f.repay(Arrays.asList(s(r,"id")),800);
        eq(2000,n(f.account(),"principalMinor"));f.callback(payment,800,"pay-1");eq(1200,n(f.receivable(order),"outstandingMinor"));eq(8800,n(f.account(),"availableMinor"));
        int journals=f.all("journals").size();f.callback(payment,800,"pay-1");f.callback(payment,800,"pay-other-delivery");eq(journals,f.all("journals").size());eq(800,f.balance("SIM_RECEIPT"));
        String refund=f.refund(order,1500);Map<String,Object> approved=f.reviewRefund(refund);eq(1200,n(approved,"principalReductionMinor"));eq(300,n(approved,"returnPayableMinor"));eq("PROCESSING",s(approved,"status"));
        eq(0,n(f.receivable(order),"outstandingMinor"));eq(10000,n(f.account(),"availableMinor"));f.callbackRefund(refund,300,"refund-1");f.callbackRefund(refund,300,"refund-1");f.callbackRefund(refund,300,"refund-repeat-reference");
        eq(500,f.balance("SIM_RECEIPT"));eq(0,f.balance("SIM_RETURN_PAYABLE"));eq(0,f.balance("SIM_AR"));eq("SUCCEEDED",s(f.get("refunds",refund),"status"));f.invariants();
    }
    private static void unknownAndMismatchedCallbacks() throws Exception {
        F f=new F();String o=f.fulfilled("s0000001",2000);String p=f.repay(Arrays.asList(s(f.receivable(o),"id")),800);int before=f.all("journals").size();
        f.call("receiveMockCallback",null,f.event("unknown",800,"unknown","PAYMENT_CONFIRMED","s0000001"),null);eq(before,f.all("journals").size());
        f.call("receiveMockCallback",null,f.event(s(f.get("repayments",p),"reference"),801,"wrong-amount","PAYMENT_CONFIRMED","s0000001"),null);eq(before,f.all("journals").size());
        f.call("receiveMockCallback",null,f.event(s(f.get("repayments",p),"reference"),800,"wrong-merchant","PAYMENT_CONFIRMED","s0000002"),null);eq(before,f.all("journals").size());
        Map<String,Object> event=f.event(s(f.get("repayments",p),"reference"),800,"success","PAYMENT_CONFIRMED","s0000001");f.call("receiveMockCallback",null,event,null);
        List<String> keys=new ArrayList<String>(event.keySet());Collections.reverse(keys);Map<String,Object> reordered=new LinkedHashMap<String,Object>();for(String key:keys)reordered.put(key,event.get(key));f.call("receiveMockCallback",null,reordered,null);
        event.put("amountMinor",700L);f.reject("EVENT_CONTENT_CONFLICT","receiveMockCallback",null,event,null);eq(800,f.balance("SIM_RECEIPT"));f.invariants();
    }
    private static void quotaExpiryAndCancellation() throws Exception {
        F f=new F();List<String> held=new ArrayList<String>();for(int i=0;i<5;i++)held.add(f.confirmed(i%2==0?"s0000001":"s0000002",2000));eq(10000,n(f.account(),"reservedMinor"));eq(0,n(f.account(),"availableMinor"));
        String extra=f.published("s0000002",1);f.reject("INSUFFICIENT_QUOTA","confirmOrder","1",f.confirmBody(extra),extra);eq(5,f.all("reservations").size());
        String first=held.get(0);f.call("cancelOrder","1",f.version("orders",first),first);f.call("cancelOrder","1",map("version",1),first);eq(2000,n(f.account(),"availableMinor"));
        Map<String,Object> heldOrder=f.get("orders",held.get(1));Map<String,Object> credential=f.call("createFulfillmentCredential","1",f.version("orders",s(heldOrder,"id")),s(heldOrder,"id"));
        f.now=f.now.plusSeconds(901);CommerceModule.expireReservations(f.context("1"));eq(0,n(f.account(),"reservedMinor"));eq(10000,n(f.account(),"availableMinor"));
        f.reject("ORDER_STATE_CONFLICT","fulfillOrder","2",map("version",n(heldOrder,"version"),"credential",s(credential,"credential")),s(heldOrder,"id"));f.invariants();
    }
    private static void snapshotsAndScope() throws Exception {
        F f=new F();String order=f.published("s0000001",2000);Map<String,Object> o=f.get("orders",order);String hash=s(o,"snapshotHash");Map<String,Object> prod=f.get("products",s(F.<Map<String,Object>>list(o,"items").get(0),"productId"));prod.put("priceMinor",2100L);f.context("2").bump(prod);
        f.reject("PRODUCT_VERSION_CHANGED","confirmOrder","1",f.confirmBody(order),order);eq(hash,s(f.get("orders",order),"snapshotHash"));eq(2000,n(f.get("orders",order),"totalMinor"));eq(0,n(f.account(),"reservedMinor"));
        String other=f.confirmed("s0000002",1000);Map<String,Object> credential=f.call("createFulfillmentCredential","1",f.version("orders",other),other);
        f.reject("SCOPE_DENIED","fulfillOrder","8",map("version",n(f.get("orders",other),"version"),"credential",s(credential,"credential")),other);
        f.reject("SCOPE_FORBIDDEN","createFulfillmentCredential","7",f.version("orders",other),other);f.invariants();
    }
    private static void repaymentAllocation() throws Exception {
        F f=new F();String one=f.fulfilled("s0000001",2000);String two=f.fulfilled("s0000001",2000);String three=f.fulfilled("s0000002",2000);
        String r1=s(f.receivable(one),"id"),r2=s(f.receivable(two),"id"),r3=s(f.receivable(three),"id");
        f.reject("RECEIVABLE_SCOPE_MISMATCH","createRepayment","1",map("merchantUid","s0000001","receivableIds",Arrays.asList(r1,r3),"amountMinor",500,"method","MOCK_ONLINE"),null);
        String p=f.repay(Arrays.asList(r2,r1),2500);f.callback(p,2500,"allocation");eq(0,n(f.get("receivables",r1),"outstandingMinor"));eq(1500,n(f.get("receivables",r2),"outstandingMinor"));
        String over=f.repay(Arrays.asList(r2),2000);f.callback(over,2000,"overpayment");eq(500,n(f.get("repayments",over),"unallocatedMinor"));eq(2000,n(f.account(),"principalMinor"));eq(-500,f.balance("SIM_RETURN_PAYABLE"));f.invariants();
    }
    private static void offlineIndependentReview() throws Exception {
        F f=new F();String order=f.fulfilled("s0000001",2000);String evidence=s(f.context("2").create("files",map("ownerId","2","status","READY","purpose","SIM_EVIDENCE")),"id");
        Map<String,Object> offline=f.call("createOfflineRepayment","2",map("userUid","u0000001","merchantUid","s0000001","receivableIds",Arrays.asList(s(f.receivable(order),"id")),"amountMinor",800,"occurredAt",f.now.toString(),"evidenceIds",Arrays.asList(evidence),"reason","合成线下凭证"),null);
        eq(2000,n(f.account(),"principalMinor"));f.reject("SELF_REVIEW_DENIED","reviewOfflineRepayment","2",map("version",n(offline,"version"),"decision","APPROVE","reason","self"),s(offline,"id"));
        f.call("reviewOfflineRepayment","1",map("version",n(offline,"version"),"decision","APPROVE","reason","用户本人核实"),s(offline,"id"));eq(1200,n(f.account(),"principalMinor"));
        f.reject("VERSION_CONFLICT","reviewOfflineRepayment","4",map("version",1,"decision","APPROVE","reason","重复"),s(offline,"id"));eq(800,f.balance("SIM_RECEIPT"));f.invariants();
    }
    private static void lateSuccessAndReallocation() throws Exception {
        F f=new F();String order=f.fulfilled("s0000001",2000);String payment=f.repay(Arrays.asList(s(f.receivable(order),"id")),800);
        f.failure(payment,false,"CANCELLED","failure-close");f.callback(payment,800,"late-event");eq("EXCEPTION",s(f.get("repayments",payment),"status"));eq(2000,n(f.account(),"principalMinor"));eq(800,n(f.get("repayments",payment),"unallocatedMinor"));
        FinanceModule.applyHeldRepayment(f.context("4"),f.get("repayments",payment),"review-1");eq(1200,n(f.account(),"principalMinor"));eq(800,f.balance("SIM_RECEIPT"));eq(0,f.balance("SIM_RETURN_PAYABLE"));f.invariants();
    }
    private static void refundFailureAndRetry() throws Exception {
        F f=new F();String o=f.fulfilled("s0000001",2000),p=f.repay(Arrays.asList(s(f.receivable(o),"id")),2000);f.callback(p,2000,"paid");String refund=f.refund(o,1500);f.reviewRefund(refund);
        f.failure(refund,true,"TIMEOUT","timeout-1");eq("PROCESSING",s(f.get("refunds",refund),"status"));f.reject("REFUND_NOT_RETRYABLE","retryRefund","4",f.version("refunds",refund),refund);
        f.failure(refund,true,"FAILED","failure-1");eq(-1500,f.balance("SIM_RETURN_PAYABLE"));f.call("retryRefund","4",f.version("refunds",refund),refund);eq("PROCESSING",s(f.get("refunds",refund),"status"));
        f.callbackRefund(refund,1500,"retried-success");eq(0,f.balance("SIM_RETURN_PAYABLE"));eq(10000,n(f.account(),"availableMinor"));f.reject("REFUND_NOT_RETRYABLE","retryRefund","4",f.version("refunds",refund),refund);f.invariants();
    }
    private static void disputesFreezeOnlyNewCredit() throws Exception {
        F f=new F();String o=f.fulfilled("s0000001",2000);Map<String,Object> d=f.call("createDispute","1",map("orderId",o,"reason","模拟异议"),null);eq(true,bool(f.account(),"blocked"));
        String newOrder=f.published("s0000001",1000);f.reject("ACCOUNT_BLOCKED","confirmOrder","1",f.confirmBody(newOrder),newOrder);
        String pay=f.repay(Arrays.asList(s(f.receivable(o),"id")),500);f.callback(pay,500,"dispute-repay");eq(1500,n(f.account(),"principalMinor"));
        String id=s(d,"id");f.call("resolveDispute","5",map("version",n(d,"version"),"resolution","核实后说明，可申诉"),id);eq(false,bool(f.account(),"blocked"));
        f.call("appealDispute","1",map("version",n(f.get("disputes",id),"version"),"resolution","独立复核"),id);eq(true,bool(f.account(),"blocked"));
        f.reject("SELF_REVIEW_DENIED","resolveDispute","5",map("version",n(f.get("disputes",id),"version"),"resolution","同人复核"),id);
        f.call("resolveDispute","6",map("version",n(f.get("disputes",id),"version"),"resolution","第二人复核"),id);eq(1500,n(f.account(),"principalMinor"));f.invariants();
    }
    private static void adjustmentsAreIndependentAndAppendOnly() throws Exception {
        F f=new F();String o=f.fulfilled("s0000001",2000);Map<String,Object> r=f.receivable(o);String original=s(r,"originalJournalId"),before=new ObjectMapper().writeValueAsString(f.get("journals",original));
        Map<String,Object> a=f.call("createAdjustment","3",map("originalJournalId",original,"receivableId",s(r,"id"),"kind","DOWNWARD","amountMinor",500,"reason","合成纠错"),null);String id=s(a,"id");
        f.reject("SELF_REVIEW_DENIED","reviewAdjustment","3",map("version",1,"decision","APPROVE","reason","self"),id);
        f.call("reviewAdjustment","4",map("version",1,"decision","APPROVE","reason","独立核查"),id);f.call("postAdjustment","4",f.version("adjustments",id),id);f.call("postAdjustment","4",map("version",1),id);
        eq(1500,n(f.account(),"principalMinor"));eq(before,new ObjectMapper().writeValueAsString(f.get("journals",original)));eq(2,f.all("journals").size());f.invariants();
    }
    private static void ruleVersionsKeepSnapshots() throws Exception {
        F f=new F();String order=f.confirmed("s0000001",2000);Map<String,Object> r=f.call("createRule","3",map("code","RV-SIM-TEST-002","quotaMinor",10000,"transactionMaxMinor",2000,"termDays",7,"credentialTtlSeconds",300,"reservationTtlSeconds",900),null);
        f.reject("SELF_REVIEW_DENIED","reviewRule","3",map("version",1,"decision","APPROVE","reason","self"),s(r,"id"));f.call("reviewRule","4",map("version",1,"decision","APPROVE","reason","模拟规则独立批准"),s(r,"id"));f.fulfill(order);
        eq(f.now.plusSeconds(30L*86400).toString(),s(f.receivable(order),"dueAt"));String next=f.fulfilled("s0000001",2000);eq(f.now.plusSeconds(7L*86400).toString(),s(f.receivable(next),"dueAt"));f.invariants();
    }
    private static void closedBusinessDayNeverBackfilled() throws Exception {
        F f=new F();String o=f.fulfilled("s0000001",2000);Map<String,Object> day=f.all("businessDays").get(0);String old=s(day,"businessDate");day.put("status","CLOSED");String p=f.repay(Arrays.asList(s(f.receivable(o),"id")),500);f.callback(p,500,"after-close");
        eq(1,f.all("journals").size());eq("RECEIVED",s(f.all("inbox").get(0),"status"));eq(2000,n(f.account(),"principalMinor"));
        f.now=f.now.plusSeconds(86400);FinanceModule.retryPendingCallbacks(f.context(null));eq("APPLIED",s(f.all("inbox").get(0),"status"));eq(1500,n(f.account(),"principalMinor"));
        Map<String,Object> last=f.all("journals").get(f.all("journals").size()-1);check(s(last,"postingDate").compareTo(old)>0,"closed date not backfilled");eq(old,s(last,"occurredDate"));f.invariants();
    }
    private static void lostNotificationRecoveredByQuery() throws Exception {
        F f=new F();String order=f.fulfilled("s0000001",2000),payment=f.repay(Arrays.asList(s(f.receivable(order),"id")),800);
        Map<String,Object> event=f.event(s(f.get("repayments",payment),"reference"),800,"withheld-payment","PAYMENT_CONFIRMED","s0000001");
        FinanceModule.simulateProviderResult(f.context("4"),event,false);eq(0,f.all("inbox").size());eq(2000,n(f.account(),"principalMinor"));
        f.reject("NOT_FOUND","getRepayment","7",map(),payment);eq(0,f.all("inbox").size());
        Map<String,Object> repayment=f.call("getRepayment","1",map(),payment);eq("CONFIRMED",s(repayment,"status"));eq(1200,n(f.account(),"principalMinor"));
        f.call("getRepayment","1",map(),payment);f.callback(payment,800,"withheld-payment");eq(800,f.balance("SIM_RECEIPT"));eq(2,f.all("journals").size());
        String refund=f.refund(order,1500);f.reviewRefund(refund);
        Map<String,Object> refundEvent=f.event(s(f.get("refunds",refund),"reference"),300,"withheld-refund","REFUND_CONFIRMED","s0000001");FinanceModule.simulateProviderResult(f.context("4"),refundEvent,false);
        eq("PROCESSING",s(f.get("refunds",refund),"status"));f.call("getRefund","1",map(),refund);eq("SUCCEEDED",s(f.get("refunds",refund),"status"));eq(500,f.balance("SIM_RECEIPT"));f.invariants();
    }
    private static void check(boolean ok,String message) {assertions++;if(!ok)throw new AssertionError(message);}
    private static void eq(Object expected,Object actual) {if(expected instanceof Number && actual instanceof Number)check(((Number)expected).longValue()==((Number)actual).longValue(),expected+" != "+actual);else check(Objects.equals(expected,actual),expected+" != "+actual);}
    private static final class F {
        SimState state=new SimState();Instant now=Instant.parse("2026-10-02T06:00:00Z");int sequence;Map<String,String> tokens=new HashMap<String,String>();final ObjectMapper json=new ObjectMapper();
        F() {
            SimContext c=context(null);for(int i=1;i<=8;i++)c.create("users",map("id",String.valueOf(i),"uid",String.format("u%07d",i),"status","ACTIVE","displayName","Synthetic "+i));
            grant("2","OWNER",null,null);grant("2","LEDGER_CHECKER",null,null);grant("3","LEDGER_MAKER",null,null);grant("3","LEDGER_CHECKER",null,null);grant("4","LEDGER_CHECKER",null,null);grant("4","SIM_CONTROLLER",null,null);grant("5","SUPPORT",null,null);grant("6","SUPPORT",null,null);
            c.create("merchants",map("id","20","uid","s0000001","ownerId","2","status","APPROVED"));c.create("merchants",map("id","21","uid","s0000002","ownerId","2","status","APPROVED"));
            c.create("stores",map("id","30","merchantUid","s0000001","active",true));c.create("stores",map("id","31","merchantUid","s0000002","active",true));grant("8","CLERK","s0000001","30");
            Map<String,Object> rule=c.create("rules",map("code","RV-SIM-TEST-001","status","APPROVED","quotaMinor",10000L,"transactionMaxMinor",2000L,"termDays",30L,"credentialTtlSeconds",300L,"reservationTtlSeconds",900L,"creatorId","3","checkerId","4"));state.meta.put("ruleId",s(rule,"id"));state.meta.put("agreementVersion","sim-terms-1");
            c.create("accounts",map("userUid","u0000001","totalMinor",10000L,"reservedMinor",0L,"principalMinor",0L,"availableMinor",10000L,"blocked",false));
        }
        void grant(String uid,String role,String merchant,String store) {context(null).create("grants",map("userId",uid,"role",role,"merchantUid",merchant,"storeId",store,"status","ACTIVE"));}
        SimContext context(String actor) {return new SimContext(state,actor,now);}
        Map<String,Object> get(String kind,String id) {return context(null).get(kind,id);}
        List<Map<String,Object>> all(String kind) {return context(null).all(kind);}
        Map<String,Object> account() {return CommerceModule.account(context("1"),"u0000001");}
        Map<String,Object> version(String kind,String id) {return map("version",n(get(kind,id),"version"));}
        @SuppressWarnings("unchecked") Map<String,Object> call(String op,String actor,Map<String,Object> b,String id) throws Exception {
            SimState before=json.readValue(json.writeValueAsBytes(state),SimState.class);try {
                SimModule module=new CommerceModule().supports(op)?new CommerceModule():new FinanceModule();return (Map<String,Object>)module.execute(op,context(actor),b==null?map():b,id==null?new HashMap<String,String>():Collections.singletonMap("id",id));
            }catch(RuntimeException e){state=before;throw e;}
        }
        void reject(String code,String op,String actor,Map<String,Object> b,String id) throws Exception {try{call(op,actor,b,id);throw new AssertionError("Expected "+code+" from "+op);}catch(SimException e){eq(code,e.code);}}
        String published(String merchant,long amount) throws Exception {
            String store="s0000001".equals(merchant)?"30":"31";Map<String,Object> product=context("2").create("products",map("storeId",store,"merchantUid",merchant,"name","合成套餐","priceMinor",amount,"active",true));
            Map<String,Object> order=call("createOrder","2",map("storeId",store,"items",Arrays.asList(map("productId",s(product,"id"),"quantity",1,"productVersion",1)),"clientReference","test-"+(++sequence)),null);
            String id=s(order,"id");Map<String,Object> token=call("publishOrder","2",map("version",n(order,"version")),id);tokens.put(id,s(token,"credential"));return id;
        }
        Map<String,Object> confirmBody(String id) {return map("version",n(get("orders",id),"version"),"credential",tokens.get(id),"snapshotHash",s(get("orders",id),"snapshotHash"),"agreementVersion","sim-terms-1","confirmed",true);}
        String confirmed(String merchant,long amount) throws Exception {String id=published(merchant,amount);call("confirmOrder","1",confirmBody(id),id);return id;}
        void fulfill(String id) throws Exception {Map<String,Object> token=call("createFulfillmentCredential","1",version("orders",id),id);call("fulfillOrder","2",map("version",n(get("orders",id),"version"),"credential",s(token,"credential")),id);}
        String fulfilled(String merchant,long amount) throws Exception {String id=confirmed(merchant,amount);fulfill(id);return id;}
        Map<String,Object> receivable(String order) {return get("receivables",s(get("orders",order),"receivableId"));}
        String repay(List<String> ids,long amount) throws Exception {return s(call("createRepayment","1",map("merchantUid",s(get("receivables",ids.get(0)),"merchantUid"),"receivableIds",ids,"amountMinor",amount,"method","MOCK_ONLINE"),null),"id");}
        Map<String,Object> event(String reference,long amount,String event,String type,String merchant) {return map("providerEventId",event,"reference",reference,"eventType",type,"amountMinor",amount,"merchantUid",merchant,"currency","CNY","occurredAt",now.toString(),"environment","SIMULATION");}
        void callback(String id,long amount,String event) throws Exception {Map<String,Object> r=get("repayments",id);call("receiveMockCallback",null,event(s(r,"reference"),amount,event,"PAYMENT_CONFIRMED",s(r,"merchantUid")),null);}
        void callbackRefund(String id,long amount,String event) throws Exception {Map<String,Object> r=get("refunds",id);call("receiveMockCallback",null,event(s(r,"reference"),amount,event,"REFUND_CONFIRMED",s(r,"merchantUid")),null);}
        String refund(String order,long amount) throws Exception {return s(call("createRefund","1",map("orderId",order,"requestedMinor",amount,"reason","合成退款"),null),"id");}
        Map<String,Object> reviewRefund(String id) throws Exception {return call("reviewRefund","4",map("version",n(get("refunds",id),"version"),"decision","APPROVE","reason","独立批准"),id);}
        void failure(String id,boolean refund,String result,String event) {Map<String,Object> r=get(refund?"refunds":"repayments",id);FinanceModule.simulateFailure(context("4"),map("reference",s(r,"reference"),"version",n(r,"version"),"result",result,"reason","合成故障","eventId",event));}
        long balance(String account) {long result=0;for(Map<String,Object> l:all("lines"))if(account.equals(s(l,"accountCode")))result+=("DR".equals(s(l,"side"))?1:-1)*n(l,"amountMinor");return result;}
        void invariants() {for(Map<String,Object> j:all("journals")){long dr=0,cr=0;for(Map<String,Object> l:all("lines"))if(s(j,"id").equals(s(l,"journalId"))){check(n(l,"amountMinor")>0,"positive journal amount");if("DR".equals(s(l,"side")))dr+=n(l,"amountMinor");else cr+=n(l,"amountMinor");}eq(dr,cr);}
            long principal=0;for(Map<String,Object> r:all("receivables")){check(n(r,"outstandingMinor")>=0,"nonnegative principal");principal+=n(r,"outstandingMinor");}eq(principal,n(account(),"principalMinor"));eq(principal,balance("SIM_AR"));eq(n(account(),"totalMinor"),n(account(),"reservedMinor")+n(account(),"principalMinor")+n(account(),"availableMinor"));}
        static <T> List<T> list(Map<?,?> r,String k) {return SimContext.<T>list(r,k);}
    }
}
