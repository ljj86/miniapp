package cn.iocoder.yudao.server.simulation;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Read model for the local development UI; never returns token hashes or evidence bytes. */
public final class SimulationWorkspace {
    public static Map<String,Object> read(SimContext c){
        c.actorId();Map<String,Object> out=map("environment","SIMULATION","warning",s(c.state.meta,"fixtureWarning"),"user",IdentityModule.userDto(c,c.actor()),"emergencyStopped",bool(c.state.meta,"emergencyStopped"));
        List<String> roles=new ArrayList<>();roles.add("USER");List<Map<String,Object>> grants=new ArrayList<>();
        for(Map<String,Object> g:c.all("grants"))if(c.actorId().equals(s(g,"userId")) && c.hasRole(s(g,"role"))){roles.add(s(g,"role"));grants.add(c.project(g,"id","role","merchantUid","storeId","bookId","validFrom","validTo","status"));}
        out.put("roleCodes",roles);out.put("scopeGrants",grants);
        Map<String,List<Map<String,Object>>> resources=new LinkedHashMap<>();
        String[] kinds={"merchants","applications","stores","products","orders","receivables","repayments","offline","refunds","disputes","adjustments","rules","files","reconciliations","differences","exports","businessDays","dayRequests","books","journals","notifications","adminRequests"};
        for(String kind:kinds){List<Map<String,Object>> rows=new ArrayList<>();for(Map<String,Object> r:c.all(kind))if(allowed(c,kind,r)){
            Map<String,Object> row=c.project(r,"id","uid","merchantUid","merchantId","storeId","bookId","userUid","orderId","receivableId","repaymentId","reference","name","displayName","status","active","version","materialVersion","reviewReason","amountMinor","totalMinor","priceMinor","receivedMinor","unallocatedMinor","issuedMinor","outstandingMinor","requestedMinor","principalReductionMinor","returnPayableMinor","overdue","hasOpenDispute","dueAt","fulfillmentStatus","snapshotHash","ruleCode","code","quotaMinor","transactionMaxMinor","termDays","credentialTtlSeconds","reservationTtlSeconds","items","createdAt","businessDate","postingDate","occurredDate","reportRevision","highWatermark","differenceType","severity","assigneeId","checkerId","type","purpose","filename","fileName","sizeBytes","sha256","title","body","read","resourceType","resourceId","action","templateCode","debitTotalMinor","creditTotalMinor","requesterId","creatorId","expiresAt","reconciliationId","channelOutcome","pendingReason");
            if("applications".equals(kind))row.put("merchantUid",s(c.get("merchants",s(r,"merchantId")),"uid"));rows.add(row);
            if(rows.size()>=100)break;
        }resources.put(kind,rows);}out.put("resources",resources);out.put("resourceLimit",100);
        for(Map<String,Object> a:c.all("accounts"))if(c.actorUid().equals(s(a,"userUid"))){out.put("account",CommerceModule.accountDto(c,a));break;}return out;
    }
    private static boolean allowed(SimContext c,String kind,Map<String,Object> r){
        String merchant=s(r,"merchantUid"),store=s(r,"storeId"),book=s(r,"bookId");boolean user=c.actorUid().equals(s(r,"userUid"))||c.actorId().equals(s(r,"userId"));
        switch(kind){
            case "merchants":return c.actorId().equals(s(r,"ownerId"))||c.hasRole("MERCHANT_REVIEWER")||c.inScope(s(r,"uid"),null,null,"LEDGER_MAKER","LEDGER_CHECKER","SIM_CONTROLLER");
            case "applications":return c.actorId().equals(s(r,"applicantId"))||c.hasRole("MERCHANT_REVIEWER");
            case "stores":return c.inScope(merchant,s(r,"id"),null,"OWNER","CLERK") || bool(r,"active")&&"APPROVED".equals(s(IdentityModule.merchant(c,merchant),"status"));
            case "products":return c.inScope(merchant,store,null,"OWNER","CLERK")||bool(r,"active")&&bool(c.get("stores",store),"active")&&"APPROVED".equals(s(IdentityModule.merchant(c,merchant),"status"));
            case "orders":return user||c.inScope(merchant,store,book,"OWNER","CLERK");
            case "receivables":return user||c.inScope(merchant,store,book,"OWNER","LEDGER_CHECKER");
            case "repayments":case "refunds":return user||c.inScope(merchant,store,book,"OWNER","LEDGER_CHECKER","SIM_CONTROLLER");
            case "offline":return user||c.inScope(merchant,store,book,"OWNER","CLERK","LEDGER_CHECKER");
            case "disputes":return user||c.inScope(merchant,store,book,"OWNER","SUPPORT");
            case "adjustments":case "reconciliations":case "differences":case "journals":return c.inScope(merchant,store,book,"LEDGER_MAKER","LEDGER_CHECKER");
            case "books":return c.inScope(merchant,null,s(r,"id"),"LEDGER_MAKER","LEDGER_CHECKER","SECURITY");
            case "businessDays":case "dayRequests":return c.inScope(merchant,null,book,"LEDGER_MAKER","LEDGER_CHECKER","SECURITY");
            case "exports":return c.actorId().equals(s(r,"requesterId"))||c.actorId().equals(s(r,"creatorId"))||c.inScope(merchant,store,book,"LEDGER_CHECKER");
            case "files":return c.actorId().equals(s(r,"ownerId"))||c.actorId().equals(s(r,"uploaderId"));
            case "notifications":return c.actorId().equals(s(r,"userId"));
            case "adminRequests":return c.hasRole("SECURITY");
            case "rules":return "APPROVED".equals(s(r,"status"))||c.hasRole("LEDGER_MAKER")||c.hasRole("LEDGER_CHECKER");
            default:return false;
        }
    }
}
