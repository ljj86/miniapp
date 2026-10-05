package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

/** Resource/config security and persistence contracts, independent of any outbound service. */
public class SupportResourceContractTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static SimContext context(EngineContractTest.F f,String actor) { return new SimContext(f.store.state,actor,f.clock.instant()); }
    private static Map<String,String> id(String id) { Map<String,String> p=new LinkedHashMap<>(); p.put("id",id); return p; }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(String op,SimContext c,Map<String,Object> b,Map<String,String> p) { return (Map<String,Object>)SupportResources.execute(op,c,b,p); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> array(String op,SimContext c,Map<String,Object> b,Map<String,String> p) { return (List<Map<String,Object>>)SupportResources.execute(op,c,b,p); }
    private static Map<String,Object> material(String text) { return map("kind","KNOWLEDGE","title","模拟资料","content",text,"audience","ALL"); }

    @Test public void seededReadsDoNotCreateRecordsAndAreAuthenticatedPlainText() throws Exception {
        EngineContractTest.F f=new EngineContractTest.F(); SimContext user=context(f,"101"), support=context(f,"501"); String before=JSON.writeValueAsString(f.store.state);
        List<Map<String,Object>> rows=array("resources.list",user,map(),new LinkedHashMap<>()); assertEquals(6,rows.size());
        assertEquals(3,array("manuals.list",user,map(),new LinkedHashMap<>()).size());
        assertEquals(1,array("updates.list",user,map(),new LinkedHashMap<>()).size());
        assertEquals("PLAIN_TEXT",s(object("manuals.detail",user,map(),id("1")),"contentFormat"));
        assertTrue(s(object("resources.detail",user,map(),id("6")),"content").contains("非发布或部署证明"));
        object("platform.ai.get",support,map(),new LinkedHashMap<>()); array("platform.payments.list",support,map(),new LinkedHashMap<>());
        assertEquals(before,JSON.writeValueAsString(f.store.state));
        rows.get(0).put("content","forged"); assertFalse(array("resources.list",user,map(),new LinkedHashMap<>()).stream().anyMatch(r->"forged".equals(s(r,"content"))));
        assertEquals(401,assertThrows(SimException.class,()->array("resources.list",context(f,null),map(),new LinkedHashMap<>())).status);
        f.store.state.table("users").get("101").put("status","DISABLED");
        assertEquals(403,assertThrows(SimException.class,()->array("resources.list",user,map(),new LinkedHashMap<>())).status);
    }
    @Test public void customMaterialVersionHistoryAndArchiveRemainImmutable() throws Exception {
        EngineContractTest.F f=new EngineContractTest.F(); SimContext c=context(f,"501"),user=context(f,"101");
        Map<String,Object> created=object("resources.create",c,material("首次内容"),new LinkedHashMap<>()); String resource=s(created,"id");
        assertEquals(1,n(created,"version"));
        String initialHistory=JSON.writeValueAsString(f.store.state.table("resourceHistory"));
        Map<String,Object> update=material("更新内容"); update.put("version",1L);
        Map<String,Object> changed=object("resources.update",c,update,id(resource)); assertEquals(2,n(changed,"version"));
        assertEquals("VERSION_CONFLICT",assertThrows(SimException.class,()->object("resources.update",c,update,id(resource))).code);
        Map<String,Object> same=material("更新内容");same.put("version",2L);
        assertEquals("MATERIAL_UNCHANGED",assertThrows(SimException.class,()->object("resources.update",c,same,id(resource))).code);
        List<Map<String,Object>> history=array("resources.history",c,map(),id(resource)); assertEquals(2,history.size());
        assertEquals("首次内容",s((Map<String,Object>)history.get(0).get("snapshot"),"content"));
        assertEquals(c.hashObject(history.get(0).get("snapshot")),s(history.get(0),"snapshotHash"));
        ((Map<String,Object>)history.get(0).get("snapshot")).put("content","tampered-return");
        assertEquals("首次内容",s((Map<String,Object>)array("resources.history",c,map(),id(resource)).get(0).get("snapshot"),"content"));
        object("resources.archive",c,map("version",2L),id(resource));
        assertEquals(404,assertThrows(SimException.class,()->object("resources.detail",user,map(),id(resource))).status);
        assertEquals("ARCHIVED",s(object("resources.detail",c,map(),id(resource)),"status"));
        assertEquals(3,array("resources.history",c,map(),id(resource)).size());
        assertEquals(6,array("resources.list",user,map(),new LinkedHashMap<>()).size());
        assertEquals(7,array("resources.adminList",c,map(),new LinkedHashMap<>()).size());
        assertEquals("STATE_CONFLICT",assertThrows(SimException.class,()->object("resources.archive",c,map("version",3L),id(resource))).code);
        SimState serialized=JSON.readValue(JSON.writeValueAsBytes(f.store.state),SimState.class);
        assertEquals("更新内容",s(object("resources.detail",new SimContext(serialized,"501",f.clock.instant()),map(),id(resource)),"content"));
        assertFalse(initialHistory.isEmpty());
    }
    @Test public void globalSupportIsRequiredAndBuiltinContentCannotBeModified() {
        EngineContractTest.F f=new EngineContractTest.F(); SimContext customer=context(f,"101"),scoped=context(f,"202"),platform=context(f,"501");
        scoped.create("grants",map("userId","202","role","SUPPORT","merchantUid","s00001","status","ACTIVE"));
        assertTrue(scoped.hasRole("SUPPORT"));assertFalse(scoped.inScope(null,null,null,"SUPPORT"));
        for(SimContext actor:Arrays.asList(customer,scoped)) {
            assertEquals(403,assertThrows(SimException.class,()->object("resources.create",actor,material("x"),new LinkedHashMap<>())).status);
            assertEquals(403,assertThrows(SimException.class,()->object("platform.ai.get",actor,map(),new LinkedHashMap<>())).status);
            assertEquals(403,assertThrows(SimException.class,()->array("platform.payments.list",actor,map(),new LinkedHashMap<>())).status);
            assertEquals(403,assertThrows(SimException.class,()->array("resources.history",actor,map(),id("1"))).status);
        }
        assertEquals("READ_ONLY_RESOURCE",assertThrows(SimException.class,()->object("resources.archive",platform,map("version",1),id("1"))).code);
        Map<String,Object> extra=material("x");extra.put("url","https://invalid.example");
        assertEquals(422,assertThrows(SimException.class,()->object("resources.create",platform,extra,new LinkedHashMap<>())).status);
    }
    @Test public void aiAndPaymentsPersistOnlyNonSecretMetadataAndNeverConnect() throws Exception {
        EngineContractTest.F f=new EngineContractTest.F(); SimContext c=context(f,"501");
        Map<String,Object> ai=object("platform.ai.get",c,map(),new LinkedHashMap<>());assertEquals(1,n(ai,"version"));assertFalse(bool(ai,"enabled"));
        Map<String,Object> b=map("version",1,"enabled",true,"model","gpt-simulation","welcomeMsg","仅模拟，不调用模型");
        Map<String,Object> configured=object("platform.ai.update",c,b,new LinkedHashMap<>());
        assertEquals(2,n(configured,"version")); assertEquals("NOT_CONNECTED",s(configured,"status"));assertFalse(bool(configured,"externalCallsEnabled"));assertFalse(bool(configured,"secretConfigured"));assertTrue(bool(configured,"enabled"));
        assertEquals("VERSION_CONFLICT",assertThrows(SimException.class,()->object("platform.ai.update",c,b,new LinkedHashMap<>())).code);
        b.put("version",2); b.put("apiKey","forbidden");
        assertEquals(422,assertThrows(SimException.class,()->object("platform.ai.update",c,b,new LinkedHashMap<>())).status);b.remove("apiKey");b.put("model","https://example.invalid/model");
        assertEquals(422,assertThrows(SimException.class,()->object("platform.ai.update",c,b,new LinkedHashMap<>())).status);
        b.put("model","sk-test-not-a-model"); assertEquals(422,assertThrows(SimException.class,()->object("platform.ai.update",c,b,new LinkedHashMap<>())).status);
        Map<String,Object> payment=object("platform.payments.update",c,map("version",1,"displayEnabled",true,"label","支付宝展示配置"),id("ALIPAY"));
        assertEquals("NOT_CONNECTED",s(payment,"status"));assertFalse(bool(payment,"ready"));assertFalse(bool(payment,"chargesEnabled"));assertFalse(bool(payment,"secretConfigured"));
        List<Map<String,Object>> payments=array("platform.payments.list",c,map(),new LinkedHashMap<>());
        assertEquals(4,payments.size());assertEquals(1,payments.stream().filter(r->bool(r,"ready")).count());assertEquals("MOCK",s(payments.stream().filter(r->bool(r,"ready")).findFirst().get(),"id"));
        Map<String,Object> bankcard=object("platform.payments.update",c,map("version",1,"displayEnabled",true,"label","银行卡展示配置"),id("BANKCARD"));
        assertEquals("NOT_CONNECTED",s(bankcard,"status"));assertFalse(bool(bankcard,"ready"));assertFalse(bool(bankcard,"chargesEnabled"));assertTrue(bool(bankcard,"configurationOnly"));
        assertEquals(3,f.store.state.table("platformConfigHistory").size());
        SimState restored=JSON.readValue(JSON.writeValueAsBytes(f.store.state),SimState.class);
        assertEquals("gpt-simulation",s(object("platform.ai.get",new SimContext(restored,"501",f.clock.instant()),map(),new LinkedHashMap<>()),"model"));
        assertFalse(JSON.writeValueAsString(f.store.state.table("supportPlatformConfig")).contains("apiKey"));
    }
    @Test public void aggregateGuardProtectsMaterialAndConfigurationEvidence() throws Exception {
        EngineContractTest.F f=new EngineContractTest.F(); SimContext c=context(f,"501");object("resources.create",c,material("不可改写"),new LinkedHashMap<>());
        object("platform.ai.update",c,map("version",1,"enabled",false,"model","unconfigured","welcomeMsg","模拟"),new LinkedHashMap<>());
        for(String table:Arrays.asList("resourceHistory","platformConfigHistory")) {
            SimState copy=JSON.readValue(JSON.writeValueAsBytes(f.store.state),SimState.class);copy.table(table).values().iterator().next().put("snapshotHash","forged");
            assertThrows(IllegalStateException.class,()->JdbcSimStore.assertAppendOnly(f.store.state,copy));
        }
    }
    @Test public void boundsFiltersAndFullReplacementAreValidated() {
        EngineContractTest.F f=new EngineContractTest.F();SimContext c=context(f,"501");
        Map<String,Object> invalid=material(" ");assertEquals(422,assertThrows(SimException.class,()->object("resources.create",c,invalid,new LinkedHashMap<>())).status);
        invalid.put("content",String.join("",Collections.nCopies(16001,"x")));assertEquals(422,assertThrows(SimException.class,()->object("resources.create",c,invalid,new LinkedHashMap<>())).status);
        Map<String,String> p=new LinkedHashMap<>();p.put("audience","CUSTOMER");
        assertTrue(array("resources.list",c,map(),p).stream().noneMatch(r->"MERCHANT".equals(s(r,"audience"))));
        p.put("q"," ");assertEquals(422,assertThrows(SimException.class,()->array("resources.list",c,map(),p)).status);
        Map<String,Object> row=object("resources.create",c,material("资料"),new LinkedHashMap<>());
        assertEquals(422,assertThrows(SimException.class,()->object("resources.update",c,map("version",1,"title","只改标题"),id(s(row,"id")))).status);
    }
}
