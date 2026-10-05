package cn.iocoder.yudao.server.simulation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyApplicationRecoveryTest {
    private static final String ORIGINAL_CREATED="2026-10-01T09:00:00Z";
    private static final class F extends EngineContractTest.F {
        String appId,merchantId;
        final Map<String,Object> original=map("merchantName","SIM 旧版餐厅","regionCode","310000","contractVersion","sim-participation-1","materialSummary","旧版合成资料");
        F(String status){
            owner=login("SIM-OWNER-001");reviewer=login("SIM-REVIEWER-001");other=login("SIM-USER-002");
            SimContext c=new SimContext(store.state,"201",clock.instant());merchantId=c.id();
            c.create("merchants",map("id",merchantId,"uid","s0012345","ownerId","201","name",original.get("merchantName"),"regionCode",original.get("regionCode"),"status","APPROVED".equals(status)?"APPROVED":"DRAFT","createdAt",ORIGINAL_CREATED));
            Map<String,Object> app=c.create("applications",map("merchantId",merchantId,"applicantId","201","status",status,"materialVersion",1L,"contractVersion",original.get("contractVersion"),"materialSummary",original.get("materialSummary"),"materialHash",c.hashObject(original),"reviewReason","","createdAt",ORIGINAL_CREATED));appId=s(app,"id");
        }
        String history(){return "/api/v1/merchant-applications/"+appId+"/history";}
        String draft(){return "/api/v1/merchant-application-drafts/"+appId;}
        String needs(){return "/api/v1/admin/merchant-applications/"+appId+"/request-information";}
        Map<String,Object> app(){return store.state.table("applications").get(appId);}
    }
    private void code(String code,Runnable command){assertEquals(code,assertThrows(SimException.class,command::run).code);}
    private long migrations(F f){return f.store.state.table("applicationHistory").values().stream().filter(r->"LEGACY_SNAPSHOT_ADAPTED".equals(s(r,"action"))).count();}

    @Test void verifiedLegacySubmissionAdaptsOnceBeforeNeedsInfoAndResubmitsWithoutInventingHistory(){
        F f=new F("SUBMITTED");Map<String,Object> read=f.data("GET",f.history(),f.owner,map());
        assertEquals(f.original,read.get("materials"));assertTrue(bool(read,"legacyMaterialsVerified"));assertTrue(bool(read,"historyAdaptationPending"));
        assertTrue(f.store.state.table("applicationMaterials").isEmpty());assertFalse(f.app().containsKey("draftMaterials"));assertEquals(1,n(f.app(),"version"));
        String key="legacy-needs-info-0001";Map<String,Object> b=map("version",1,"reason","补充旧版合成说明");
        f.req("POST",f.needs(),f.reviewer,b,key,new LinkedHashMap<>());
        assertEquals("NEEDS_INFO",s(f.app(),"status"));assertEquals(2,n(f.app(),"version"));assertEquals(1,migrations(f));assertEquals(1,f.store.state.table("applicationMaterials").size());
        Map<String,Object> snapshot=f.store.state.table("applicationMaterials").values().iterator().next();String snapshotId=s(snapshot,"id"),snapshotJson=f.store.json.valueToTree(snapshot).toString();
        assertEquals(ORIGINAL_CREATED,s(snapshot,"submittedAt"));assertEquals(ORIGINAL_CREATED,s(snapshot,"originalCreatedAt"));assertEquals("201",s(snapshot,"submittedBy"));assertEquals("301",s(snapshot,"adaptedBy"));assertTrue(bool(snapshot,"legacyAdapted"));
        Map<String,Object> event=f.store.state.table("applicationHistory").values().iterator().next();assertEquals("SUBMITTED",s(event,"status"));assertEquals(1,n(event,"applicationVersion"));assertEquals(ORIGINAL_CREATED,s(event,"originalCreatedAt"));
        assertEquals(1,f.store.state.table("audit").values().stream().filter(r->"LEGACY_APPLICATION_SNAPSHOT_ADAPTED".equals(s(r,"action"))).count());
        f.req("POST",f.needs(),f.reviewer,b,key,new LinkedHashMap<>());assertEquals(1,migrations(f));assertEquals(1,f.store.state.table("applicationMaterials").size());
        f.data("PATCH",f.draft(),f.owner,map("version",2,"materialSummary","补充后的第二版合成资料"));
        assertEquals("旧版合成资料",s((Map)f.data("GET",f.history(),f.reviewer,map()).get("materials"),"materialSummary"));
        f.data("POST",f.draft()+"/submit",f.owner,map("version",3));assertEquals(2,n(f.app(),"materialVersion"));assertEquals(2,n(f.app(),"submittedMaterialVersion"));assertEquals(2,f.store.state.table("applicationMaterials").size());
        assertEquals(snapshotJson,f.store.json.valueToTree(f.store.state.table("applicationMaterials").get(snapshotId)).toString());assertEquals(1,migrations(f));
    }
    @Test void hashMismatchRejectsReadAndWritesWithoutCreatingFalseEvidence(){
        F f=new F("SUBMITTED");f.store.state.table("merchants").get(f.merchantId).put("name","SIM 被覆盖的新名称");
        code("APPLICATION_HISTORY_UNAVAILABLE",()->f.data("GET",f.history(),f.owner,map()));
        code("APPLICATION_HISTORY_UNAVAILABLE",()->f.data("POST",f.needs(),f.reviewer,map("version",1,"reason","不能伪造历史")));
        code("APPLICATION_HISTORY_UNAVAILABLE",()->f.data("POST","/api/v1/admin/merchant-applications/"+f.appId+"/decision",f.reviewer,map("version",1,"decision","APPROVE","reason","不能带着未知历史通过")));
        assertTrue(f.store.state.table("applicationMaterials").isEmpty());assertEquals(0,migrations(f));assertFalse(f.app().containsKey("draftMaterials"));assertEquals("SUBMITTED",s(f.app(),"status"));assertEquals(1,n(f.app(),"version"));
    }
    @Test void unauthorizedAndStaleRequestsCannotTriggerLegacyAdaptation(){
        F f=new F("SUBMITTED");code("SCOPE_DENIED",()->f.data("GET",f.history(),f.other,map()));
        code("SCOPE_DENIED",()->f.data("POST",f.needs(),f.owner,map("version",1,"reason","无权复核")));
        code("VERSION_CONFLICT",()->f.data("POST",f.needs(),f.reviewer,map("version",2,"reason","过期版本")));
        for(Map<String,Object> grant:f.store.state.table("grants").values())if("301".equals(s(grant,"userId")))grant.put("merchantUid","s9999999");
        code("SCOPE_DENIED",()->f.data("POST",f.needs(),f.reviewer,map("version",1,"reason","越权复核")));
        assertTrue(f.store.state.table("applicationMaterials").isEmpty());assertEquals(0,migrations(f));assertFalse(f.app().containsKey("submittedMaterialVersion"));
    }
    @Test void terminalLegacyApplicationsStayTerminalAndReadOnly(){
        for(String status:Arrays.asList("APPROVED","REJECTED")){
            F f=new F(status);f.app().put("version",3L);Map<String,Object> read=f.data("GET",f.history(),f.owner,map());assertEquals(status,s(read,"status"));assertEquals(3,n(read,"version"));assertEquals(f.original,read.get("materials"));
            code("STATE_CONFLICT",()->f.data("PATCH",f.draft(),f.owner,map("version",3,"materialSummary","不能重新打开")));
            code("STATE_CONFLICT",()->f.data("POST",f.draft()+"/submit",f.owner,map("version",3)));
            code("STATE_CONFLICT",()->f.data("POST",f.needs(),f.reviewer,map("version",3,"reason","不能重新打开")));
            assertEquals(status,s(f.app(),"status"));assertEquals(3,n(f.app(),"version"));assertTrue(f.store.state.table("applicationMaterials").isEmpty());assertEquals(0,migrations(f));
        }
    }
    @Test void unavailableEarlierVersionsOrMissingOriginalTimestampAreNeverSynthesized(){
        F f=new F("SUBMITTED");f.app().put("materialVersion",2L);
        code("APPLICATION_HISTORY_UNAVAILABLE",()->f.data("POST",f.needs(),f.reviewer,map("version",1,"reason","缺少前一版本")));
        f.app().put("materialVersion",1L);f.app().remove("createdAt");
        code("APPLICATION_HISTORY_UNAVAILABLE",()->f.data("POST",f.needs(),f.reviewer,map("version",1,"reason","缺少原提交时间")));
        assertTrue(f.store.state.table("applicationMaterials").isEmpty());assertEquals(0,migrations(f));
    }
}
