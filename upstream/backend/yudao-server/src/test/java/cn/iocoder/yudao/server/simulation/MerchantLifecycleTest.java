package cn.iocoder.yudao.server.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

class MerchantLifecycleTest {
    private EngineContractTest.F fixture(){EngineContractTest.F f=new EngineContractTest.F();f.owner=f.login("SIM-OWNER-001");f.other=f.login("SIM-USER-002");f.reviewer=f.login("SIM-REVIEWER-001");f.controller=f.login("SIM-CONTROLLER-001");return f;}
    private Map<String,Object> materials(){return map("merchantName","SIM 草稿餐厅","regionCode","310000","contractVersion","sim-participation-1","materialSummary","合成摘要第一版");}
    private Map<String,Object> draft(EngineContractTest.F f){return f.data("POST","/api/v1/merchant-application-drafts",f.owner,materials());}
    private String path(Map<String,Object> a){return "/api/v1/merchant-application-drafts/"+s(a,"id");}
    private void code(String code,Runnable command){assertEquals(code,assertThrows(SimException.class,command::run).code);}

    @Test void partialDraftCanBeCompletedAndSubmittedMaterialsLock(){
        EngineContractTest.F f=fixture();Map<String,Object> a=f.data("POST","/api/v1/merchant-application-drafts",f.owner,map("merchantName","SIM 未完成草稿"));String path=path(a);
        assertEquals("DRAFT",s(a,"status"));assertTrue((Boolean)a.get("simulationOnly"));
        code("VALIDATION_FAILED",()->f.data("POST",path+"/submit",f.owner,map("version",1)));
        Map<String,Object> update=materials();update.put("version",1);a=f.data("PATCH",path,f.owner,update);assertEquals(2,n(a,"version"));
        Map<String,Object> submitted=f.data("POST",path+"/submit",f.owner,map("version",2));assertEquals("SUBMITTED",s(submitted,"status"));assertEquals(1,n(submitted,"materialVersion"));
        code("STATE_CONFLICT",()->f.data("PATCH",path,f.owner,map("version",3,"materialSummary","偷偷替换")));
        assertEquals(1,f.store.state.table("applicationMaterials").size());
        Map<String,Object> read=f.data("GET","/api/v1/merchant-applications/"+s(a,"id"),f.reviewer,map());assertEquals("SUBMITTED",s(read,"status"));
    }
    @Test void requestInformationPreservesFirstVersionAndResubmitsNewImmutableVersion(){
        EngineContractTest.F f=fixture();Map<String,Object> draft=draft(f);String path=path(draft),review="/api/v1/admin/merchant-applications/"+s(draft,"id")+"/request-information";
        Map<String,Object> submitted=f.data("POST",path+"/submit",f.owner,map("version",1));
        String firstHash=s(f.store.state.table("applicationMaterials").values().iterator().next(),"materialHash");
        Map<String,Object> needs=f.data("POST",review,f.reviewer,map("version",2,"reason","请补充合成摘要"));assertEquals("NEEDS_INFO",s(needs,"status"));assertEquals("请补充合成摘要",s(needs,"reviewReason"));
        code("STATE_CONFLICT",()->f.data("POST",path+"/submit",f.owner,map("version",3)));
        Map<String,Object> edit=f.data("PATCH",path,f.owner,map("version",3,"materialSummary","合成摘要第二版"));assertEquals("NEEDS_INFO",s(edit,"status"));
        Map<String,Object> resubmitted=f.data("POST",path+"/submit",f.owner,map("version",4));assertEquals(2,n(resubmitted,"materialVersion"));assertEquals(2,n(resubmitted,"submittedMaterialVersion"));assertEquals(2,f.store.state.table("applicationMaterials").size());
        assertEquals(firstHash,s(f.store.state.table("applicationMaterials").values().iterator().next(),"materialHash"));
        code("VERSION_CONFLICT",()->f.data("POST",review,f.reviewer,map("version",2,"reason","过期审核")));
        Map<String,Object> approved=f.data("POST","/api/v1/admin/merchant-applications/"+s(draft,"id")+"/decision",f.reviewer,map("version",5,"decision","APPROVE","reason","独立复核第二版资料"));assertEquals("APPROVED",s(approved,"status"));
        Map<String,Object> history=f.data("GET","/api/v1/merchant-applications/"+s(draft,"id")+"/history",f.owner,map());assertEquals(6,((List)history.get("history")).size());assertEquals(2,((List)history.get("materialHistory")).size());
    }
    @Test void draftAuthorizationVersionValidationAndIdempotencyShareCentralBoundary(){
        EngineContractTest.F f=fixture();Map<String,Object> b=materials();String key="draft-replay-key-0001";
        Map<String,Object> response=f.req("POST","/api/v1/merchant-application-drafts",f.owner,b,key,new LinkedHashMap<>());
        assertEquals(f.store.json.valueToTree(response).toString(),f.store.json.valueToTree(f.req("POST","/api/v1/merchant-application-drafts",f.owner,new TreeMap<>(b),key,new LinkedHashMap<>())).toString());
        Map<String,Object> changed=materials();changed.put("merchantName","SIM changed");code("IDEMPOTENCY_CONFLICT",()->f.req("POST","/api/v1/merchant-application-drafts",f.owner,changed,key,new LinkedHashMap<>()));
        Map<String,Object> a=(Map<String,Object>)response.get("data");String path=path(a);
        code("SCOPE_DENIED",()->f.data("GET",path,f.other,map()));code("SCOPE_DENIED",()->f.data("PATCH",path,f.other,map("version",1,"materialSummary","stranger")));
        code("AUTH_REQUIRED",()->f.data("PATCH",path,null,map("version",1,"materialSummary","anonymous")));
        code("VERSION_CONFLICT",()->f.data("PATCH",path,f.owner,map("version",99,"materialSummary","stale")));
        code("VALIDATION_FAILED",()->f.data("PATCH",path,f.owner,map("version",1,"materialSummary","okay","applicantId","202")));
        code("VALIDATION_FAILED",()->f.data("PATCH",path,f.owner,map("version",1.5,"materialSummary","bad")));
        code("VALIDATION_FAILED",()->f.req("PATCH",path,f.owner,map("version",1,"materialSummary","bad"),"short",new LinkedHashMap<>()));
        code("STATE_CONFLICT",()->f.data("POST","/api/v1/merchant-applications",f.owner,materials()));
        assertEquals(1,f.store.state.table("applications").size());
    }
    @Test void selfReviewAndScopedReviewerCannotEscapeTheirGrant(){
        EngineContractTest.F f=fixture();Map<String,Object> a=draft(f);String path=path(a),review="/api/v1/admin/merchant-applications/"+s(a,"id")+"/request-information";f.data("POST",path+"/submit",f.owner,map("version",1));
        String ownerId=s(f.store.state.table("applications").get(s(a,"id")),"applicantId");new SimContext(f.store.state,ownerId,f.clock.instant()).create("grants",map("userId",ownerId,"role","MERCHANT_REVIEWER","status","ACTIVE"));
        code("SELF_REVIEW_DENIED",()->f.data("POST",review,f.owner,map("version",2,"reason","自己审核")));
        String reviewerId=IdentityModule.authenticate(new SimContext(f.store.state,null,f.clock.instant()),f.reviewer);
        for(Map<String,Object> grant:f.store.state.table("grants").values())if(reviewerId.equals(s(grant,"userId"))&&"MERCHANT_REVIEWER".equals(s(grant,"role")))grant.put("merchantUid","s9999999");
        code("SCOPE_DENIED",()->f.data("POST",review,f.reviewer,map("version",2,"reason","跨域审核")));
        code("SCOPE_DENIED",()->f.data("GET","/api/v1/merchant-applications/"+s(a,"id")+"/history",f.reviewer,map()));
        code("SCOPE_DENIED",()->f.data("GET","/api/v1/merchant-applications/"+s(a,"id"),f.reviewer,map()));
    }
    @Test void replayRechecksActiveGrantsAndSupplementalMutationsAreRateLimited(){
        EngineContractTest.F f=fixture();String key="draft-scope-key-0001";f.req("POST","/api/v1/merchant-application-drafts",f.owner,materials(),key,new LinkedHashMap<>());
        String ownerId=IdentityModule.authenticate(new SimContext(f.store.state,null,f.clock.instant()),f.owner);new SimContext(f.store.state,ownerId,f.clock.instant()).create("grants",map("userId",ownerId,"role","OWNER","merchantUid","s1234567","status","ACTIVE"));
        code("SCOPE_DENIED",()->f.req("POST","/api/v1/merchant-application-drafts",f.owner,materials(),key,new LinkedHashMap<>()));
        f.clock.tick(61);String appId=f.store.state.table("applications").keySet().iterator().next();String path="/api/v1/merchant-application-drafts/"+appId;
        for(int i=0;i<30;i++)f.data("PATCH",path,f.owner,map("version",i+1,"materialSummary","rate test "+i));
        code("RATE_LIMITED",()->f.data("PATCH",path,f.owner,map("version",31,"materialSummary","overflow")));
    }
    @Test void reviewerCannotReadUnsubmittedOrWorkingDraftMaterials(){
        EngineContractTest.F f=fixture();Map<String,Object> a=draft(f);String path=path(a),history="/api/v1/merchant-applications/"+s(a,"id")+"/history";
        code("SCOPE_DENIED",()->f.data("GET",history,f.reviewer,map()));
        code("SCOPE_DENIED",()->f.data("GET","/api/v1/merchant-applications/"+s(a,"id"),f.reviewer,map()));
        f.data("POST",path+"/submit",f.owner,map("version",1));
        f.data("POST","/api/v1/admin/merchant-applications/"+s(a,"id")+"/request-information",f.reviewer,map("version",2,"reason","补充说明"));
        f.data("PATCH",path,f.owner,map("version",3,"materialSummary","私有未提交草稿"));
        assertEquals("合成摘要第一版",s((Map)f.data("GET",history,f.reviewer,map()).get("materials"),"materialSummary"));
        assertEquals("私有未提交草稿",s((Map)f.data("GET",history,f.owner,map()).get("materials"),"materialSummary"));
    }
    @Test void supplementalFilterRequiresBearerAndRejectsUndeclaredPayload()throws Exception{
        EngineContractTest.F f=fixture();SimulationApiFilter filter=new SimulationApiFilter(f.engine);
        for(String auth:Arrays.asList("Basic "+f.owner,"Token "+f.owner)){
            MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/v1/merchant-application-drafts");request.addHeader("Authorization",auth);request.setContentType("application/json");request.setContent("{}".getBytes(StandardCharsets.UTF_8));MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->fail("Should be handled"));assertEquals(401,response.getStatus());
        }
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/v1/merchant-application-drafts");request.addHeader("Authorization","Bearer "+f.owner);request.addHeader("Idempotency-Key","filter-draft-key-001");request.setContentType("application/json");request.setContent("{\"admin\":true}".getBytes(StandardCharsets.UTF_8));MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->fail("Should be handled"));assertEquals(422,response.getStatus());assertTrue(f.store.state.table("applications").isEmpty());
    }
}
