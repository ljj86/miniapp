package cn.iocoder.yudao.server.simulation;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkspaceBoundaryTest {
    final Instant now=Instant.parse("2026-10-03T05:00:00Z");
    SimState fixture(){
        SimState state=SimulationSeed.create(now);SimContext c=new SimContext(state,null,now);
        for(int i=1;i<=2;i++){
            c.create("merchants",map("id","m"+i,"uid","s000000"+i,"ownerId",i==1?"201":"102","status","DRAFT","name","SIM merchant"));
            c.create("applications",map("id","a"+i,"merchantId","m"+i,"applicantId",i==1?"201":"102","status","SUBMITTED","submittedMaterialVersion",1L,"materialVersion",1L,"draftMaterials",map("materialSummary","unsubmitted private edit")));
            c.create("applicationMaterials",map("applicationId","a"+i,"materialVersion",1L,"materials",map("merchantName","SIM","materialSummary","submitted version")));
        }
        for(Map<String,Object> g:c.all("grants"))if("301".equals(s(g,"userId")))g.put("merchantUid","s0000001");
        return state;
    }
    Map<String,Object> read(SimState state,String actor){return SimulationWorkspace.read(new SimContext(state,actor,now));}
    List<Map<String,Object>> rows(Map<String,Object> view,String kind){return (List<Map<String,Object>>)((Map)view.get("resources")).get(kind);}
    @Test void merchantReviewerScopeDoesNotWidenToOtherApplications(){
        Map<String,Object> view=read(fixture(),"301");assertEquals(1,rows(view,"applications").size());assertEquals("a1",rows(view,"applications").get(0).get("id"));assertEquals(1,rows(view,"merchants").size());
    }
    @Test void unsubmittedDraftAndEditsStayPrivate(){
        SimState state=fixture();state.table("applications").get("a1").put("status","DRAFT");assertTrue(rows(read(state,"301"),"applications").isEmpty());
        assertEquals("unsubmitted private edit",((Map)rows(read(state,"201"),"applications").get(0).get("materials")).get("materialSummary"));
        state.table("applications").get("a1").put("status","NEEDS_INFO");assertEquals("submitted version",((Map)rows(read(state,"301"),"applications").get(0).get("materials")).get("materialSummary"));
    }
    @Test void inactiveOrExpiredGrantsAreNotAdvertised(){
        SimState state=fixture();SimContext c=new SimContext(state,null,now);
        c.create("grants",map("userId","301","role","MERCHANT_REVIEWER","status","REVOKED","merchantUid","s0000002"));
        c.create("grants",map("userId","301","role","MERCHANT_REVIEWER","status","ACTIVE","validTo",now.minusSeconds(1).toString()));
        assertEquals(1,((List)read(state,"301").get("scopeGrants")).size());
    }
    @Test void notificationsAndFailureSettingRequireTheirActualScope(){
        SimState state=fixture();SimContext c=new SimContext(state,null,now);
        NotificationModule.enqueue(c,"201","SIM one","application","a1","TEST",1L);
        NotificationModule.enqueue(c,"102","SIM two","application","a2","TEST",1L);
        Map<String,Object> owner=read(state,"201");assertEquals(1,rows(owner,"notificationOutbox").size());assertFalse(owner.containsKey("notificationFailureConfig"));
        Map<String,Object> controller=read(state,"701");assertEquals(2,rows(controller,"notificationOutbox").size());assertTrue(controller.containsKey("notificationFailureConfig"));
        for(Map<String,Object> g:c.all("grants"))if("701".equals(s(g,"userId")))g.put("merchantUid","s0000001");
        Map<String,Object> scoped=read(state,"701");assertTrue(rows(scoped,"notificationOutbox").isEmpty());assertFalse(scoped.containsKey("notificationFailureConfig"));
    }
}
