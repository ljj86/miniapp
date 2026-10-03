package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent adversarial checks through the contract/auth/transaction boundary.
 * The serialized store verifies rollback and racing commands, not real database isolation.
 */
public class IndependentSecurityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CALLBACK_KEY = "INDEPENDENT_TEST_EPHEMERAL_RANDOM_KEY_32_PLUS_BYTES";
    private static Map<String,String> query(String... pairs) {
        Map<String,String> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put(pairs[i],pairs[i+1]);return out;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> data(Object envelope) {return (Map<String,Object>)((Map<String,Object>)envelope).get("data");}
    private static String wire(Object value) {try{return JSON.writeValueAsString(value);}catch(Exception e){throw new AssertionError(e);}}
    private static EngineContractTest.F fixture(){EngineContractTest.F f=new EngineContractTest.F();MockKeyRegistry keys=new MockKeyRegistry();keys.add("INDEPENDENT-KEY-1",CALLBACK_KEY.getBytes(StandardCharsets.UTF_8),f.clock.instant().minusSeconds(1),f.clock.instant().plusSeconds(86400));f.engine=new SimulationEngine(f.store,f.clock,keys);f.setup();return f;}
    private static Map<String,Object> row(EngineContractTest.F f,String table,String id){return f.store.state.table(table).get(id);}
    private static Map<String,Object> account(EngineContractTest.F f){return f.data("GET","/api/v1/me/limit-account",f.user,map());}
    private static Map<String,Object> draft(EngineContractTest.F f,String product){return f.data("POST","/api/v1/orders",f.owner,map("storeId",f.storeId,"items",Arrays.asList(map("productId",product,"quantity",1,"productVersion",n(row(f,"products",product),"version"))),"clientReference","SIM-independent-"+UUID.randomUUID()));}
    private static Map<String,Object> confirmation(EngineContractTest.F f,Map<String,Object> order){
        String id=s(order,"id");Map<String,Object> credential=f.data("POST","/api/v1/orders/"+id+"/publish",f.owner,map("version",n(order,"version")));
        Map<String,Object> snapshot=f.data("POST","/api/v1/order-credentials/resolve",f.user,map("credential",s(credential,"credential")));
        return map("version",n(snapshot,"version"),"credential",s(credential,"credential"),"snapshotHash",s(snapshot,"snapshotHash"),"agreementVersion","sim-terms-1","confirmed",true);
    }
    private static Map<String,Object> confirm(EngineContractTest.F f){Map<String,Object> order=draft(f,f.productId);return f.data("POST","/api/v1/orders/"+s(order,"id")+"/confirm",f.user,confirmation(f,order));}
    private static Map<String,Object> payment(EngineContractTest.F f,Map<String,Object> ar,long amount){return f.data("POST","/api/v1/repayments",f.user,map("merchantUid",f.merchant,"receivableIds",Arrays.asList(s(ar,"id")),"amountMinor",amount,"method","MOCK_ONLINE"));}
    private static Map<String,Object> event(EngineContractTest.F f,Map<String,Object> payment,String eventId,long amount){return map("providerEventId",eventId,"reference",s(payment,"reference"),"eventType","PAYMENT_CONFIRMED","amountMinor",amount,"merchantUid",f.merchant,"currency","CNY","occurredAt",f.clock.instant().toString(),"environment","SIMULATION");}
    private static Map<String,String> signature(String timestamp,String nonce,byte[] raw){try{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(CALLBACK_KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.US_ASCII));mac.update(raw);mac.update((byte)'\n');
        return query("x-mock-key-id","INDEPENDENT-KEY-1","x-mock-timestamp",timestamp,"x-mock-nonce",nonce,"x-mock-signature",Base64.getEncoder().encodeToString(mac.doFinal()));
    }catch(Exception e){throw new AssertionError(e);}}
    private static Object callback(EngineContractTest.F f,Map<String,Object> event,String nonce){byte[] raw=wire(event).getBytes(StandardCharsets.UTF_8);return f.engine.request("POST","/api/v1/callbacks/mock",event,query("_remote","callback"),signature(Long.toString(f.clock.instant().getEpochSecond()),nonce,raw),raw,UUID.randomUUID().toString());}
    private static void balanced(EngineContractTest.F f){for(Map<String,Object> j:f.store.state.table("journals").values()){
        long dr=0,cr=0;for(Map<String,Object> l:f.store.state.table("lines").values())if(s(j,"id").equals(s(l,"journalId"))){assertTrue(n(l,"amountMinor")>0);if("DR".equals(s(l,"side")))dr+=n(l,"amountMinor");else if("CR".equals(s(l,"side")))cr+=n(l,"amountMinor");else fail("Unrecognized side");}
        assertTrue(dr>0);assertEquals(dr,cr);assertEquals(dr,n(j,"debitTotalMinor"));assertEquals(cr,n(j,"creditTotalMinor"));assertEquals("SIMULATION",s(j,"environment"));assertEquals("CNY",s(j,"currency"));
    }}
    private static List<Object> race(Supplier<?> a,Supplier<?> b)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Object>> pending=new ArrayList<>();for(Supplier<?> s:Arrays.asList(a,b))pending.add(pool.submit(()->{start.await();try{return s.get();}catch(SimException e){return e;}}));start.countDown();return Arrays.asList(pending.get(0).get(20,TimeUnit.SECONDS),pending.get(1).get(20,TimeUnit.SECONDS));}
        finally{pool.shutdownNow();}
    }

    @Test public void pathTargetCannotBeSubstitutedOnAnIdempotencyReplay(){
        EngineContractTest.F f=fixture();String second=s(f.data("POST","/api/v1/products",f.owner,map("storeId",f.storeId,"name","second","priceMinor",500)),"id");
        Map<String,Object> body=map("version",1,"name","updated");String key="independent-target-key-01";
        f.req("PATCH","/api/v1/products/"+f.productId,f.owner,body,key,query());
        SimException error=assertThrows(SimException.class,()->f.req("PATCH","/api/v1/products/"+second,f.owner,body,key,query()),"A changed target must not replay a different object's success");
        assertEquals(409,error.status);assertEquals("IDEMPOTENCY_CONFLICT",error.code);assertEquals("second",s(row(f,"products",second),"name"));
    }
    @Test public void cachedSuccessDoesNotSurviveRevokedObjectScope(){
        EngineContractTest.F f=fixture();Map<String,Object> order=draft(f,f.productId);String path="/api/v1/orders/"+s(order,"id")+"/publish",key="independent-revoked-key-01";Map<String,Object> body=map("version",1);
        f.req("POST",path,f.owner,body,key,query());
        f.store.transaction(state->{for(Map<String,Object> grant:state.table("grants").values())if("201".equals(s(grant,"userId"))&&f.merchant.equals(s(grant,"merchantUid")))grant.put("status","REVOKED");new SimContext(state,"201",f.clock.instant()).create("grants",map("userId","201","role","OWNER","merchantUid","s9999999","status","ACTIVE"));state.meta.put("grantVersion",n(state.meta,"grantVersion")+1);return null;});
        SimException denied=assertThrows(SimException.class,()->f.req("POST",path,f.owner,body,key,query()),"Cached credentials must remain object-scoped after grant revocation");
        assertTrue(denied.status==403||denied.status==409);
    }
    @Test public void merchantCannotExitWithHeldReservations(){
        EngineContractTest.F f=fixture();confirm(f);Map<String,Object> merchant=f.store.state.table("merchants").values().iterator().next();
        assertEquals(2000,n(account(f),"reservedMinor"));
        assertThrows(SimException.class,()->f.data("POST","/api/v1/admin/merchants/"+s(merchant,"id")+"/state",f.reviewer,map("version",n(merchant,"version"),"status","EXITED","reason","synthetic exit review")),"Held reservations must block exit before delivery");
        assertEquals("APPROVED",s(row(f,"merchants",s(merchant,"id")),"status"));assertEquals(2000,n(account(f),"reservedMinor"));
    }
    @Test public void grantRequiresIndependentReviewerAndExpiresAtExactBoundary(){
        EngineContractTest.F f=fixture();String first=f.login("SIM-SECURITY-001"),second=f.login("SIM-SECURITY-002"),clerk=f.login("SIM-CLERK-001");
        Map<String,Object> grant=f.data("POST","/api/v1/admin/scope-grants",first,map("userUid","y00000202","scopeKey","STORE:"+f.storeId,"roleCode","OWNER","expiresAt",f.clock.instant().plusSeconds(10).toString(),"reason","temporary synthetic store grant"));String id=s(grant,"resourceId");
        SimException self=assertThrows(SimException.class,()->f.data("POST","/api/v1/admin/scope-grants/"+id+"/decision",first,map("version",1,"decision","APPROVE","reason","independent synthetic review")));assertEquals("SELF_REVIEW_DENIED",self.code);assertNull(row(f,"adminRequests",id).get("checkerId"));assertEquals("PENDING",s(row(f,"adminRequests",id),"status"));
        f.data("POST","/api/v1/admin/scope-grants/"+id+"/decision",second,map("version",1,"decision","APPROVE","reason","independent synthetic review"));
        f.data("PATCH","/api/v1/products/"+f.productId,clerk,map("version",1,"name","within store grant"));
        assertThrows(SimException.class,()->f.data("POST","/api/v1/stores",clerk,map("merchantUid",f.merchant,"name","forbidden merchant-wide","regionCode","310000","addressText","SIM only")));
        f.clock.tick(10);assertThrows(SimException.class,()->f.data("PATCH","/api/v1/products/"+f.productId,clerk,map("version",2,"name","expired grant")));assertEquals("within store grant",s(row(f,"products",f.productId),"name"));
    }
    @Test public void simultaneousRefreshReplayRevokesEveryDescendantButNotOtherFamilies()throws Exception{
        EngineContractTest.F f=new EngineContractTest.F();Map<String,Object> ch=f.data("POST","/api/v1/auth/sms-challenges",null,map("phone","SIM-USER-001","purpose","LOGIN"));Map<String,Object> session=f.data("POST","/api/v1/auth/sessions",null,map("provider","PHONE_OTP","phone","SIM-USER-001","challengeId",s(ch,"challengeId"),"code","246810"));
        f.clock.tick(60);String otherFamily=f.login("SIM-USER-001");String old=s(session,"refreshToken");
        List<Object> results=race(()->f.data("POST","/api/v1/auth/sessions/refresh",null,map("refreshToken",old)),()->f.data("POST","/api/v1/auth/sessions/refresh",null,map("refreshToken",old)));
        assertEquals(1,results.stream().filter(x->x instanceof SimException).count());for(Object result:results)if(result instanceof Map){String access=s((Map)result,"accessToken");assertThrows(SimException.class,()->f.data("GET","/api/v1/me",access,map()));}
        assertEquals("y00000101",s(f.data("GET","/api/v1/me",otherFamily,map()),"uid"));
    }
    @Test public void simultaneousSameKeyAndVersionProduceOneDurableEffect()throws Exception{
        EngineContractTest.F f=fixture();Map<String,Object> body=map("storeId",f.storeId,"name","raced synthetic product","priceMinor",500);int count=f.store.state.table("products").size();String key="independent-race-key-01";
        List<Object> results=race(()->f.req("POST","/api/v1/products",f.owner,body,key,query()),()->f.req("POST","/api/v1/products",f.owner,body,key,query()));
        assertEquals(wire(results.get(0)),wire(results.get(1)));assertEquals(count+1,f.store.state.table("products").size());String id=s(data(results.get(0)),"id");
        List<Object> updates=race(()->f.req("PATCH","/api/v1/products/"+id,f.owner,map("version",1,"name","alpha"),"independent-version-key-a",query()),()->f.req("PATCH","/api/v1/products/"+id,f.owner,map("version",1,"name","beta"),"independent-version-key-b",query()));
        assertEquals(1,updates.stream().filter(x->x instanceof SimException && "VERSION_CONFLICT".equals(((SimException)x).code)).count());assertEquals(2,n(row(f,"products",id),"version"));
    }
    @Test public void simultaneousConfirmationsCannotOverspendSharedQuota()throws Exception{
        EngineContractTest.F f=fixture();f.store.transaction(state->{for(Map<String,Object> a:state.table("accounts").values())if("y00000101".equals(s(a,"userUid"))){a.put("totalMinor",3000L);a.put("availableMinor",3000L);}return null;});
        Map<String,Object> a=draft(f,f.productId),b=draft(f,f.productId),ba=confirmation(f,a),bb=confirmation(f,b);
        List<Object> results=race(()->f.req("POST","/api/v1/orders/"+s(a,"id")+"/confirm",f.user,ba,"independent-quota-key-a",query()),()->f.req("POST","/api/v1/orders/"+s(b,"id")+"/confirm",f.user,bb,"independent-quota-key-b",query()));
        assertEquals(1,results.stream().filter(x->x instanceof SimException&&"INSUFFICIENT_QUOTA".equals(((SimException)x).code)).count());Map<String,Object> balance=account(f);assertEquals(2000,n(balance,"reservedMinor"));assertEquals(1000,n(balance,"availableMinor"));assertEquals(1,f.store.state.table("reservations").size());
        f.clock.tick(900);f.engine.sweep();
        // The original 900-second access token expires with the reservation.
        f.user=f.login("SIM-USER-001");
        assertEquals(0,n(account(f),"reservedMinor"));assertEquals(3000,n(account(f),"availableMinor"));
    }
    @Test public void signedCallbacksBindRawBytesTimeNonceAndBusinessEventSeparately(){
        EngineContractTest.F f=fixture();Map<String,Object> ar=f.fulfill(),repayment=payment(f,ar,800),event=event(f,repayment,"SIM-independent-callback",800);byte[] raw=wire(event).getBytes(StandardCharsets.UTF_8);String now=Long.toString(f.clock.instant().getEpochSecond());
        Map<String,String> original=signature(now,"independent_nonce_001",raw);byte[] whitespace=(wire(event)+" ").getBytes(StandardCharsets.UTF_8);
        assertEquals(401,assertThrows(SimException.class,()->f.engine.request("POST","/api/v1/callbacks/mock",event,query("_remote","callback"),original,whitespace,"raw-byte-negative")).status);
        Map<String,String> expired=signature(Long.toString(f.clock.instant().getEpochSecond()-301),"independent_nonce_002",raw);
        assertEquals(401,assertThrows(SimException.class,()->f.engine.request("POST","/api/v1/callbacks/mock",event,query("_remote","callback"),expired,raw,"expired-negative")).status);assertTrue(f.store.state.table("signatureNonces").isEmpty());
        Object accepted=callback(f,event,"independent_nonce_003");int journals=f.store.state.table("journals").size();String ledger=wire(f.store.state.table("journals"));
        assertEquals(409,assertThrows(SimException.class,()->callback(f,event,"independent_nonce_003")).status);
        assertEquals(s(data(accepted),"resourceId"),s(data(callback(f,event,"independent_nonce_004")),"resourceId"));
        Map<String,Object> duplicate=new LinkedHashMap<>(event);duplicate.put("providerEventId","SIM-independent-other-event");callback(f,duplicate,"independent_nonce_005");
        assertEquals(1200,n(row(f,"receivables",s(ar,"id")),"outstandingMinor"));assertEquals(journals,f.store.state.table("journals").size());assertEquals(ledger,wire(f.store.state.table("journals")));assertEquals(2,f.store.state.table("inbox").size());balanced(f);
        Map<String,Object> changed=new LinkedHashMap<>(event);changed.put("amountMinor",801);assertEquals("EVENT_CONTENT_CONFLICT",assertThrows(SimException.class,()->callback(f,changed,"independent_nonce_006")).code);assertEquals(journals,f.store.state.table("journals").size());
    }
    @Test public void latePaymentPreservesPrincipalAndCreatesPayableOnlyOnce(){
        EngineContractTest.F f=fixture();Map<String,Object> ar=f.fulfill(),repayment=payment(f,ar,800);f.engine.failure(map("reference",s(repayment,"reference"),"version",n(repayment,"version"),"result","CANCELLED","reason","synthetic provider cancellation","eventId","SIM-independent-failure"),f.controller,"late-case");
        Map<String,Object> event=event(f,repayment,"SIM-independent-late",800);callback(f,event,"independent_late_nonce1");callback(f,event,"independent_late_nonce2");
        assertEquals(2000,n(row(f,"receivables",s(ar,"id")),"outstandingMinor"));assertEquals(2000,n(account(f),"principalMinor"));Map<String,Object> current=row(f,"repayments",s(repayment,"id"));assertEquals(800,n(current,"receivedMinor"));assertEquals(800,n(current,"unallocatedMinor"));assertEquals("EXCEPTION",s(current,"status"));assertEquals(1,f.store.state.table("differences").size());assertEquals(2,f.store.state.table("journals").size());balanced(f);
    }
    @Test public void paginationBindsActorFiltersGrantVersionAndExpiry(){
        EngineContractTest.F f=fixture();f.data("POST","/api/v1/products",f.owner,map("storeId",f.storeId,"name","second page","priceMinor",100));
        String cursor=s(data(f.req("GET","/api/v1/products",f.owner,map(),"not-applicable",query("limit","1"))),"nextCursor");assertFalse(cursor.isEmpty());
        assertThrows(SimException.class,()->f.req("GET","/api/v1/products",f.other,map(),"not-applicable",query("limit","1","cursor",cursor)));
        assertThrows(SimException.class,()->f.req("GET","/api/v1/products",f.owner,map(),"not-applicable",query("limit","2","cursor",cursor)));
        assertEquals(1,list(data(f.req("GET","/api/v1/products",f.owner,map(),"not-applicable",query("limit","1","cursor",cursor))),"items").size());
        f.store.transaction(state->{state.meta.put("grantVersion",n(state.meta,"grantVersion")+1);return null;});
        assertThrows(SimException.class,()->f.req("GET","/api/v1/products",f.owner,map(),"not-applicable",query("limit","1","cursor",cursor)));
    }
    @Test public void privateExportRequiresRequesterScopeLiveTokenAndIntegrity(){
        EngineContractTest.F f=fixture();f.fulfill();Map<String,Object> evidence=f.data("POST","/api/v1/files",f.owner,map("file","SIM synthetic export review evidence","purpose","SIM_EVIDENCE"));
        Map<String,Object> exp=f.data("POST","/api/v1/exports",f.owner,map("merchantUid",f.merchant,"dateFrom","2026-10-01","dateTo","2026-10-03","purpose","synthetic export verification"));String id=s(exp,"id");
        f.data("POST","/api/v1/admin/exports/"+id+"/decision",f.checker,map("version",1,"decision","APPROVE","reason","independent synthetic evidence review","evidenceIds",Arrays.asList(s(evidence,"id"))));
        Map<String,Object> link=f.data("GET","/api/v1/exports/"+id+"/download",f.owner,map());String token=s(link,"url").split("token=",2)[1];
        byte[] content=f.engine.download(id,token,f.owner);assertTrue(new String(content,StandardCharsets.UTF_8).contains("SIMULATED_NOT_LEGAL_ACCOUNTING"));
        assertThrows(SimException.class,()->f.engine.download(id,token,null));assertThrows(SimException.class,()->f.engine.download(id,token,f.other));assertThrows(SimException.class,()->f.engine.download(id,"wrong-token",f.owner));
        f.clock.tick(300);assertEquals(410,assertThrows(SimException.class,()->f.engine.download(id,token,f.owner)).status);
    }
    @Test public void workspaceAndPublicFixturesExposeNoSecurityMaterial(){
        EngineContractTest.F f=fixture();Map<String,Object> order=draft(f,f.productId);Map<String,Object> credential=f.data("POST","/api/v1/orders/"+s(order,"id")+"/publish",f.owner,map("version",1));
        String publicJson=wire(f.engine.fixtures()),workspaceJson=wire(f.engine.workspace(f.owner));
        for(String forbidden:Arrays.asList("accessHash","refreshHash","phoneLookup","codeHash","tokenHash","cursorSecret","contentBase64","downloadTokenHash","TEST_ONLY_NOT_A_PRODUCTION_KEY")){assertFalse(publicJson.contains(forbidden),forbidden);assertFalse(workspaceJson.contains(forbidden),forbidden);}
        assertFalse(workspaceJson.contains(f.owner));assertFalse(workspaceJson.contains(s(credential,"credential")));assertFalse(publicJson.contains(f.owner));
    }
}
