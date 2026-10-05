package cn.iocoder.yudao.server.simulation;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;
class PaginationRecoveryTest {
    final Instant now=Instant.parse("2026-10-03T10:00:00Z");
    Map<String,String> q(String... parts){Map<String,String> q=new LinkedHashMap<>();for(int i=0;i<parts.length;i+=2)q.put(parts[i],parts[i+1]);return q;}
    List<Map<String,Object>> rows(){return Arrays.asList(map("id","2","createdAt","2026-10-03T01:00:00Z"),map("id","999","createdAt","2026-10-02T23:00:00Z"),map("id","100","createdAt","2026-10-02T23:00:00Z"));}
    SimContext context(SimState state,String actor){return new SimContext(state,actor,now);}
    List<String> ids(Map<String,Object> page){List<String> out=new ArrayList<>();for(Map<String,Object> r:SimContext.<Map<String,Object>>list(page,"items"))out.add(s(r,"id"));return out;}
    void invalid(Runnable action){SimException e=assertThrows(SimException.class,action::run);assertEquals(422,e.status);}
    @Test void actualCreationTimeAndIdTiesControlBothSortOrders(){
        SimContext c=context(SimulationSeed.create(now),"101");
        assertEquals(Arrays.asList("100","999","2"),ids(c.page(rows(),q("sort","createdAt_asc"))));
        assertEquals(Arrays.asList("2","999","100"),ids(c.page(rows(),q("sort","createdAt_desc"))));
    }
    @Test void keysetRetainsEqualTimestampRowsWithoutDuplicates(){
        SimContext c=context(SimulationSeed.create(now),"101");Map<String,String> p=q("sort","createdAt_asc","limit","1","_operation","listOrders");
        Map<String,Object> first=c.page(rows(),p);assertEquals(Collections.singletonList("100"),ids(first));p.put("cursor",s(first,"nextCursor"));
        Map<String,Object> second=c.page(rows(),p);assertEquals(Collections.singletonList("999"),ids(second));p.put("cursor",s(second,"nextCursor"));
        Map<String,Object> third=c.page(rows(),p);assertEquals(Collections.singletonList("2"),ids(third));assertFalse(bool(third,"hasMore"));
    }
    @Test void datesUseInclusiveShanghaiDaysAndRejectReversedOrInvalidRanges(){
        SimContext c=context(SimulationSeed.create(now),"101");List<Map<String,Object>> input=Arrays.asList(map("id","1","createdAt","2026-10-02T15:59:59Z"),map("id","2","createdAt","2026-10-02T16:00:00Z"),map("id","3","createdAt","2026-10-03T15:59:59Z"),map("id","4","createdAt","2026-10-03T16:00:00Z"));
        assertEquals(Arrays.asList("2","3"),ids(c.page(input,q("dateFrom","2026-10-03","dateTo","2026-10-03","sort","createdAt_asc"))));
        invalid(()->c.page(input,q("dateFrom","2026-10-04","dateTo","2026-10-03")));
        invalid(()->c.page(input,q("dateFrom","2026-02-30")));
    }
    @Test void cursorBindsActorOperationAndFiltersButNotTransientConnectionMetadata(){
        SimState state=SimulationSeed.create(now);SimContext c=context(state,"101");Map<String,String> p=q("limit","1","_operation","listOrders","_remote","one");
        String cursor=s(c.page(rows(),p),"nextCursor");p.put("cursor",cursor);
        invalid(()->context(state,"102").page(rows(),p));
        Map<String,String> different=new LinkedHashMap<>(p);different.put("_operation","listProducts");invalid(()->c.page(rows(),different));
        Map<String,String> changed=new LinkedHashMap<>(p);changed.put("dateFrom","2026-10-03");invalid(()->c.page(rows(),changed));
        p.put("_remote","two");p.put("_accessHash","rotated-session");assertEquals(1,ids(c.page(rows(),p)).size());
    }
    @Test void actualGrantExpiryInvalidatesCursorWithoutGrantVersionChange(){
        SimState state=SimulationSeed.create(now);SimContext c=context(state,"201");
        c.create("grants",map("userId","201","role","OWNER","merchantUid","s0000001","status","ACTIVE","validTo",now.plusSeconds(1).toString()));
        Map<String,String> p=q("limit","1");p.put("cursor",s(c.page(rows(),p),"nextCursor"));
        invalid(()->new SimContext(state,"201",now.plusSeconds(2)).page(rows(),p));
    }
    @Test void cursorTamperingAndExpiryAreRejected(){
        SimState state=SimulationSeed.create(now);SimContext c=context(state,"101");Map<String,String> p=q("limit","1");String cursor=s(c.page(rows(),p),"nextCursor");
        p.put("cursor",cursor+"x");invalid(()->c.page(rows(),p));p.put("cursor",cursor);invalid(()->new SimContext(state,"101",now.plusSeconds(900)).page(rows(),p));
    }
    @Test void sourceCreationMetadataDoesNotLeakIntoFrozenDto(){
        SimState state=SimulationSeed.create(now);SimContext c=context(state,"101");c.create("stores",map("id","100","createdAt","2026-10-02T16:00:00Z","privateEvidence","not-for-response"));
        Map<String,Object> page=c.page(Collections.singletonList(map("id","100","name","SIM")),q("dateFrom","2026-10-03","dateTo","2026-10-03"),"stores");
        assertEquals(map("id","100","name","SIM"),SimContext.<Map<String,Object>>list(page,"items").get(0));
    }
    @Test void originalStoreProductAndReceivableResponsesKeepTheirSchemasAtDateBoundary(){
        EngineContractTest.F f=new EngineContractTest.F();f.clock.value=Instant.parse("2026-10-02T16:00:00Z");f.setup();f.fulfill();
        for(String path:Arrays.asList("/api/v1/stores","/api/v1/products","/api/v1/receivables")){
            Map<String,Object> wrapper=f.req("GET",path,f.user,map(),"pagination-check-00001",q("dateFrom","2026-10-03","dateTo","2026-10-03"));
            Map<String,Object> data=(Map<String,Object>)wrapper.get("data");assertEquals(1,ids(data).size(),path);
            Map<String,Object> excluded=(Map<String,Object>)f.req("GET",path,f.user,map(),"pagination-check-00002",q("dateTo","2026-10-02")).get("data");assertTrue(ids(excluded).isEmpty(),path);
        }
    }
}
