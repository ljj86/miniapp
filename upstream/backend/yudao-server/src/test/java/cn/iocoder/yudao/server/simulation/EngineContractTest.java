package cn.iocoder.yudao.server.simulation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

public class EngineContractTest {
    static class Memory implements SimStore {
        SimState state=SimulationSeed.create(Instant.parse("2026-10-02T10:00:00Z"));ObjectMapper json=new ObjectMapper();
        public synchronized <T>T transaction(Function<SimState,T> f){try{SimState working=json.readValue(json.writeValueAsBytes(state),SimState.class);T result=f.apply(working);state=working;return result;}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}}
    }
    static class Time extends Clock {Instant value=Instant.parse("2026-10-02T10:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return value;}void tick(long seconds){value=value.plusSeconds(seconds);}}
    static class F {
        Memory store=new Memory();Time clock=new Time();SimulationEngine engine=new SimulationEngine(store,clock);String user,owner,reviewer,checker,maker,controller,other;String merchant,storeId,productId;int key;
        @SuppressWarnings("unchecked") Map<String,Object> req(String method,String path,String token,Map<String,Object> b){return req(method,path,token,b,"test-idempotency-"+(++key),new LinkedHashMap<>());}
        @SuppressWarnings("unchecked") Map<String,Object> req(String method,String path,String token,Map<String,Object> b,String idempotency,Map<String,String> query){Map<String,String> h=new LinkedHashMap<>();h.put("authorization",token);h.put("idempotency-key",idempotency);query.put("_remote","test");try{return (Map<String,Object>)engine.request(method,path,b,query,h,new ObjectMapper().writeValueAsBytes(b),UUID.randomUUID().toString());}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}}
        @SuppressWarnings("unchecked") Map<String,Object> data(String method,String path,String token,Map<String,Object> b){return (Map<String,Object>)req(method,path,token,b).get("data");}
        String login(String phone){Map<String,Object> ch=data("POST","/api/v1/auth/sms-challenges",null,map("phone",phone,"purpose","LOGIN"));return s(data("POST","/api/v1/auth/sessions",null,map("provider","PHONE_OTP","phone",phone,"challengeId",s(ch,"challengeId"),"code","246810")),"accessToken");}
        void setup(){
            owner=login("SIM-OWNER-001");user=login("SIM-USER-001");other=login("SIM-USER-002");reviewer=login("SIM-REVIEWER-001");checker=login("SIM-CHECKER-001");maker=login("SIM-MAKER-001");controller=login("SIM-CONTROLLER-001");
            Map<String,Object> app=data("POST","/api/v1/merchant-applications",owner,map("merchantName","SIM 测试餐厅","regionCode","310000","contractVersion","sim-participation-1","materialSummary","完全合成模拟资料"));String appId=s(app,"id");
            data("POST","/api/v1/admin/merchant-applications/"+appId+"/decision",reviewer,map("version",1,"decision","APPROVE","reason","独立验证合成参与资料"));
            merchant=s(store.state.table("merchants").get(s(app,"merchantId")),"uid");Map<String,Object> st=data("POST","/api/v1/stores",owner,map("merchantUid",merchant,"name","模拟门店","regionCode","310000","addressText","SIM合成地址"));storeId=s(st,"id");
            productId=s(data("POST","/api/v1/products",owner,map("storeId",storeId,"name","模拟套餐","priceMinor",2000)),"id");
            data("POST","/api/v1/me/simulation-activation",user,map("purpose","SIMULATION","documentVersion","sim-terms-1","accepted",true));
        }
        Map<String,Object> fulfill(){Map<String,Object> order=data("POST","/api/v1/orders",owner,map("storeId",storeId,"items",Arrays.asList(map("productId",productId,"quantity",1,"productVersion",1)),"clientReference","SIM-order-"+(++key)));
            String id=s(order,"id");Map<String,Object> credential=data("POST","/api/v1/orders/"+id+"/publish",owner,map("version",n(order,"version")));
            Map<String,Object> snapshot=data("POST","/api/v1/order-credentials/resolve",user,map("credential",s(credential,"credential")));
            Map<String,Object> confirmed=data("POST","/api/v1/orders/"+id+"/confirm",user,map("version",n(snapshot,"version"),"credential",s(credential,"credential"),"snapshotHash",s(snapshot,"snapshotHash"),"agreementVersion","sim-terms-1","confirmed",true));
            Map<String,Object> current=(Map<String,Object>)confirmed.get("order");Map<String,Object> fc=data("POST","/api/v1/orders/"+id+"/fulfillment-credential",user,map("version",n(current,"version")));
            return data("POST","/api/v1/orders/"+id+"/fulfill",owner,map("version",n(fc,"orderVersion"),"credential",s(fc,"credential")));
        }
    }
    @Test public void endToEndResponsesMatchFrozenContract(){F f=new F();f.setup();Map<String,Object> ar=f.fulfill();assertEquals(2000,n(ar,"outstandingMinor"));
        Map<String,Object> repayment=f.data("POST","/api/v1/repayments",f.user,map("merchantUid",f.merchant,"receivableIds",Arrays.asList(s(ar,"id")),"amountMinor",800,"method","MOCK_ONLINE"));
        Map<String,Object> event=map("providerEventId","SIM-contract-payment-001","reference",s(repayment,"reference"),"eventType","PAYMENT_CONFIRMED","amountMinor",800,"merchantUid",f.merchant,"currency","CNY","occurredAt",f.clock.instant().toString(),"environment","SIMULATION");
        f.data("POST","/api/v1/simulator/results",f.controller,event);assertEquals(1200,n(f.data("GET","/api/v1/receivables/"+s(ar,"id"),f.user,map()),"outstandingMinor"));
        Map<String,Object> refund=f.data("POST","/api/v1/refunds",f.user,map("orderId",s(ar,"orderId"),"requestedMinor",1500,"reason","模拟套餐部分退款"));refund=f.data("POST","/api/v1/admin/refunds/"+s(refund,"id")+"/decision",f.checker,map("version",n(refund,"version"),"decision","APPROVE","reason","独立核查退款依据"));
        assertEquals(1200,n(refund,"principalReductionMinor"));assertEquals(300,n(refund,"returnPayableMinor"));
        f.data("POST","/api/v1/simulator/results",f.controller,map("providerEventId","SIM-contract-refund-001","reference",s(refund,"reference"),"eventType","REFUND_CONFIRMED","amountMinor",300,"merchantUid",f.merchant,"currency","CNY","occurredAt",f.clock.instant().toString(),"environment","SIMULATION"));
        assertEquals(0,n(f.data("GET","/api/v1/me/limit-account",f.user,map()),"principalMinor"));assertEquals("SUCCEEDED",s(f.data("GET","/api/v1/refunds/"+s(refund,"id"),f.user,map()),"status"));
    }
    @Test public void schemaUnknownFieldsRejectedWithoutMutation(){F f=new F();f.setup();int before=f.store.state.table("products").size();SimException e=assertThrows(SimException.class,()->f.data("POST","/api/v1/products",f.owner,map("storeId",f.storeId,"name","bad","priceMinor",100,"admin",true)));assertEquals(422,e.status);assertEquals(before,f.store.state.table("products").size());}
    @Test public void idempotencyCanonicalBodyReplayAndConflict(){F f=new F();f.setup();Map<String,Object> b=map("storeId",f.storeId,"name","SIM重复保护","priceMinor",500);String key="same-key-for-product-001";
        Map<String,Object> a=f.req("POST","/api/v1/products",f.owner,b,key,new LinkedHashMap<>());Map<String,Object> shuffled=map("priceMinor",500,"name","SIM重复保护","storeId",f.storeId);Map<String,Object> again=f.req("POST","/api/v1/products",f.owner,shuffled,key,new LinkedHashMap<>());assertEquals(new ObjectMapper().valueToTree(a).toString(),new ObjectMapper().valueToTree(again).toString());
        shuffled.put("priceMinor",501);SimException e=assertThrows(SimException.class,()->f.req("POST","/api/v1/products",f.owner,shuffled,key,new LinkedHashMap<>()));assertEquals("IDEMPOTENCY_CONFLICT",e.code);}
    @Test public void refreshReplayRevokesWholeFamily(){F f=new F();Map<String,Object> ch=f.data("POST","/api/v1/auth/sms-challenges",null,map("phone","SIM-USER-001","purpose","LOGIN"));Map<String,Object> one=f.data("POST","/api/v1/auth/sessions",null,map("provider","PHONE_OTP","phone","SIM-USER-001","challengeId",s(ch,"challengeId"),"code","246810"));Map<String,Object> two=f.data("POST","/api/v1/auth/sessions/refresh",null,map("refreshToken",s(one,"refreshToken")));
        assertThrows(SimException.class,()->f.data("POST","/api/v1/auth/sessions/refresh",null,map("refreshToken",s(one,"refreshToken"))));assertThrows(SimException.class,()->f.data("GET","/api/v1/me",s(two,"accessToken"),map()));}
    @Test public void wrongOtpAttemptsPersistAndNoRealPhone(){F f=new F();assertThrows(SimException.class,()->f.data("POST","/api/v1/auth/sms-challenges",null,map("phone","13800000000","purpose","LOGIN")));Map<String,Object> ch=f.data("POST","/api/v1/auth/sms-challenges",null,map("phone","SIM-USER-001","purpose","LOGIN"));
        for(int i=0;i<5;i++)assertThrows(SimException.class,()->f.data("POST","/api/v1/auth/sessions",null,map("provider","PHONE_OTP","phone","SIM-USER-001","challengeId",s(ch,"challengeId"),"code","000000")));
        assertEquals(5,n(f.store.state.table("challenges").get(s(ch,"challengeId")),"attempts"));SimException e=assertThrows(SimException.class,()->f.data("POST","/api/v1/auth/sessions",null,map("provider","PHONE_OTP","phone","SIM-USER-001","challengeId",s(ch,"challengeId"),"code","246810")));assertEquals(410,e.status);}
    @Test public void defaultDenyAndWorkspaceNoForeignOrders(){F f=new F();f.setup();Map<String,Object> ar=f.fulfill();assertThrows(SimException.class,()->f.data("GET","/api/v1/receivables/"+s(ar,"id"),f.other,map()));assertThrows(SimException.class,()->f.data("GET","/api/v1/me",null,map()));Map workspace=(Map)f.engine.workspace(f.other);Map resources=(Map)workspace.get("resources");assertTrue(((List)resources.get("orders")).isEmpty());assertTrue(((List)resources.get("receivables")).isEmpty());}
    @Test public void storeGrantCannotExpandToMerchantWideScope(){F f=new F();SimContext c=new SimContext(f.store.state,"202",f.clock.instant());c.create("grants",map("userId","202","role","LEDGER_CHECKER","merchantUid","s0000001","storeId","11","status","ACTIVE"));assertTrue(c.hasRole("LEDGER_CHECKER"));assertTrue(c.inScope("s0000001","11",null,"LEDGER_CHECKER"));assertFalse(c.inScope("s0000001",null,null,"LEDGER_CHECKER"));assertFalse(c.inScope("s0000001","12",null,"LEDGER_CHECKER"));}
    @Test public void anonymousHealthAndOriginalOperationCoverage(){F f=new F();assertEquals("UP",s(f.data("GET","/api/v1/health",null,map()),"status"));Set<String> ids=new HashSet<>();List<SimModule> modules=Arrays.asList(new IdentityModule(),new MerchantModule(),new CommerceModule(),new FinanceModule(),new ReconciliationModule());for(ContractCatalog.Operation op:f.engine.catalog.operations){ids.add(op.id);assertTrue(modules.stream().anyMatch(m->m.supports(op.id)),op.id);}assertEquals(76,ids.size());}
}
