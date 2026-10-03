package cn.iocoder.yudao.server.simulation;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Fast executable domain regressions; not a replacement for HTTP/MySQL acceptance. */
public final class ReconciliationModuleScenarios {
    private static final ReconciliationModule M=new ReconciliationModule();
    private static final String MERCHANT="s0000001";
    private static final Instant NOW=Instant.parse("2026-10-02T16:01:00Z");
    private static int assertions=0;
    public static void main(String[] args){
        testBusinessDays();testReviewExpiryAndVersion();testExportSeparationAndExpiry();testCsvAndDuplicateImport();testFourWayDifferences();testNotificationPrivacyAndStop();testLateAllocation();testPositiveLateAllocation();testNonemptyCloseAndTampering();eq(ReconciliationModule.workingHoursAfter(Instant.parse("2026-10-02T08:00:00Z"),4),Instant.parse("2026-10-05T03:00:00Z"),"working SLA skips weekend");
        System.out.println("PASS: "+assertions+" domain assertions; no claim of MySQL, HTTP, concurrency or human acceptance");
    }
    private static SimState seed(){
        SimState st=new SimState();st.meta.put("cursorSecret","TEST_ONLY_CURSOR_SEED_RECON_SCENARIOS");st.meta.put("requestId","00000000-0000-0000-0000-000000000001");
        SimContext c=new SimContext(st,null,NOW);for(int i=1;i<=5;i++)c.create("users",map("id",Integer.toString(i),"uid",String.format("y%08d",i),"status","ACTIVE","displayName","Synthetic "+i));
        grant(c,"1","LEDGER_MAKER");grant(c,"1","LEDGER_CHECKER");grant(c,"2","LEDGER_CHECKER");grant(c,"2","LEDGER_MAKER");grant(c,"3","OWNER");grant(c,"5","SECURITY");
        return st;
    }
    private static void grant(SimContext c,String uid,String role){c.create("grants",map("userId",uid,"role",role,"merchantUid",MERCHANT,"status","ACTIVE"));}
    private static SimContext ctx(SimState s,String actor){return new SimContext(s,actor,NOW);}
    @SuppressWarnings("unchecked") private static Map<String,Object> call(SimContext c,String op,Map<String,Object> body,Map<String,String> p){return (Map<String,Object>)M.execute(op,c,body,p);}
    private static Map<String,String> p(String... args){Map<String,String> p=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)p.put(args[i],args[i+1]);return p;}
    private static void eq(Object a,Object b,String msg){assertions++;if(!Objects.equals(String.valueOf(a),String.valueOf(b)))throw new AssertionError(msg+": actual="+a+" expected="+b);}
    private static void yes(boolean b,String msg){assertions++;if(!b)throw new AssertionError(msg);}
    private static void fails(String code,Runnable r){assertions++;try{r.run();throw new AssertionError("Expected "+code);}catch(SimException e){if(!code.equals(e.code))throw new AssertionError("Expected "+code+" got "+e.code,e);}}
    private static String evidence(SimContext c){return s(call(c,"uploadFile",map("purpose","SIM_EVIDENCE","_fileBytes","SYNTHETIC REVIEW EVIDENCE ONLY".getBytes(StandardCharsets.UTF_8)),p()),"id");}
    private static Map<String,Object> reconciliation(SimContext c,String businessDate){String bill=ReconciliationModule.buildChannelBill(c,MERCHANT,businessDate);Map<String,Object> file=call(c,"uploadFile",map("purpose","RECON_IMPORT","_fileBytes",bill.getBytes(StandardCharsets.UTF_8)),p());return call(c,"createReconciliation",map("merchantUid",MERCHANT,"businessDate",businessDate,"fileId",s(file,"id"),"fileSha256",s(file,"sha256")),p());}
    private static Map<String,Object> request(SimContext c,String book,String date,long version,String recon,String evidence,String op){return call(c,op,map("expectedDayVersion",version,"reason","Synthetic report review reason","impactSummary","Synthetic no-amount-change report impact","evidenceIds",Arrays.asList(evidence),"reconciliationIds",Arrays.asList(recon)),p("bookId",book,"businessDate",date));}
    private static Map<String,Object> approve(SimContext c,Map<String,Object> r,String ev){return call(c,"reviewBusinessDayRequest",map("version",n(r,"version"),"decision","APPROVE","reason","Independent synthetic review","evidenceIds",Arrays.asList(ev)),p("id",s(r,"id")));}
    private static Map<String,Object> execute(SimContext c,Map<String,Object> r){return call(c,"executeBusinessDayRequest",map("version",n(r,"version")),p("id",s(r,"id")));}
    private static void testBusinessDays(){
        SimState st=seed();SimContext a=ctx(st,"1"),b=ctx(st,"2");String book=FinanceModule.bookFor(a,MERCHANT),ev=evidence(a),ev2=evidence(b);Map<String,Object> recon=reconciliation(a,"2026-10-02");eq(s(recon,"status"),"MATCHED","empty bill reconciles");
        Map<String,Object> r=request(a,book,"2026-10-02",1,s(recon,"id"),ev,"requestBusinessDayClose");yes(r.containsKey("checkerId") && r.get("checkerId")==null,"nullable checker preserved in exact DTO");
        fails("SELF_REVIEW_DENIED",()->approve(a,r,ev));Map<String,Object> approved=approve(b,r,ev2);Map<String,Object> executed=execute(b,approved);eq(s(executed,"status"),"EXECUTED","close executed");
        Map<String,Object> d=a.all("businessDays").get(0);eq(s(d,"status"),"CLOSED","closed day");yes(s(d,"snapshotHash").matches("[a-f0-9]{64}"),"snapshot hash persisted");eq(a.all("reportSnapshots").size(),1,"one immutable report");String originalHash=s(d,"snapshotHash");
        Map<String,Object> re=request(a,book,"2026-10-02",n(d,"version"),s(recon,"id"),ev,"requestBusinessDayReopen");execute(b,approve(b,re,ev2));eq(s(d,"status"),"REOPENED_REVIEW","review reopen state");eq(s(d,"snapshotHash"),originalHash,"reopen retains old report");
        Map<String,Object> rc=request(a,book,"2026-10-02",n(d,"version"),s(recon,"id"),ev,"requestBusinessDayReclose");execute(b,approve(b,rc,ev2));eq(s(d,"status"),"CLOSED","reclose state");eq(n(d,"reportRevision"),2,"report revision increments");eq(a.all("reportSnapshots").size(),2,"old and new report retained");eq(s(a.all("reportSnapshots").get(0),"snapshotHash"),originalHash,"old report immutable");
        Map<String,Object> page=call(a,"listBusinessDays",map(),p("bookId",book,"page","1","pageSize","20"));eq(n(page,"total"),1,"business-day page totals");
        Map<String,Object> j=FinanceModule.postJournal(a,MERCHANT,"y00000004","J-03","repayments","900","late-day-test","2026-10-02T15:00:00Z",Arrays.asList(FinanceModule.line("SIM_RECEIPT","DR",100,null,null),FinanceModule.line("SIM_RETURN_PAYABLE","CR",100,null,null)));
        eq(s(j,"postingDate"),"2026-10-03","late event posts current day");eq(s(j,"occurredDate"),"2026-10-02","late event original date preserved");
    }
    private static void testReviewExpiryAndVersion(){
        SimState st=seed();SimContext a=ctx(st,"1"),b=ctx(st,"2");String book=FinanceModule.bookFor(a,MERCHANT),ev=evidence(a);Map<String,Object> re=reconciliation(a,"2026-10-02");Map<String,Object> r=request(a,book,"2026-10-02",1,s(re,"id"),ev,"requestBusinessDayClose");
        fails("REQUEST_EXPIRED",()->approve(new SimContext(st,"2",NOW.plusSeconds(86400)),r,ev));Map<String,Object> ok=approve(b,r,ev);Map<String,Object> day=a.all("businessDays").get(0);a.bump(day);fails("DAY_VERSION_CONFLICT",()->execute(b,ok));
        SimState early=seed();SimContext x=ctx(early,"1");String eb=FinanceModule.bookFor(x,MERCHANT),ee=evidence(x);Map<String,Object> er=reconciliation(x,"2026-10-03");fails("BUSINESS_DAY_NOT_WRITABLE",()->request(x,eb,"2026-10-03",1,s(er,"id"),ee,"requestBusinessDayClose"));
        fails("SCOPE_DENIED",()->call(ctx(st,"4"),"listBusinessDays",map(),p("bookId",book)));
    }
    private static void testExportSeparationAndExpiry(){
        SimState st=seed();SimContext owner=ctx(st,"3"),checker=ctx(st,"2"),maker=ctx(st,"1");String ev=evidence(checker);Map<String,Object> e=call(owner,"createExport",map("merchantUid",MERCHANT,"dateFrom","2026-10-01","dateTo","2026-10-03","purpose","Synthetic report review"),p());
        grant(owner,"3","LEDGER_CHECKER");fails("SELF_REVIEW_DENIED",()->call(owner,"reviewExport",map("version",1L,"decision","APPROVE","reason","Independent review","evidenceIds",Arrays.asList(ev)),p("id",s(e,"id"))));
        Map<String,Object> ready=call(checker,"reviewExport",map("version",1L,"decision","APPROVE","reason","Independent review","evidenceIds",Arrays.asList(ev)),p("id",s(e,"id")));eq(s(ready,"status"),"READY","approved export ready");
        fails("RESOURCE_NOT_FOUND",()->call(maker,"getExport",map(),p("id",s(e,"id"))));Map<String,Object> link=call(owner,"getExportDownload",map(),p("id",s(e,"id")));String url=s(link,"url"),token=url.substring(url.indexOf("token=")+6);String csv=new String(M.downloadExport(owner,s(e,"id"),token),StandardCharsets.UTF_8);yes(csv.contains("SIMULATED_EMPTY_REPORT") && csv.contains("y00000003"),"watermark carries recipient even empty report");
        fails("SCOPE_DENIED",()->M.downloadExport(owner,s(e,"id"),"bad"));fails("CREDENTIAL_EXPIRED",()->M.downloadExport(new SimContext(st,"3",NOW.plusSeconds(301)),s(e,"id"),token));
        Map<String,Object> expired=call(new SimContext(st,"3",NOW.plusSeconds(3601)),"getExport",map(),p("id",s(e,"id")));eq(s(expired,"status"),"EXPIRED","export expires");
    }
    private static void testCsvAndDuplicateImport(){
        SimState st=seed();SimContext a=ctx(st,"1");Map<String,Object> event=map("providerEventId","event-1","reference","payment-1","eventType","PAYMENT_CONFIRMED","amountMinor",800L,"merchantUid",MERCHANT,"currency","CNY","occurredAt","2026-10-02T10:00:00Z","environment","SIMULATION");a.create("inbox",map("providerEventId","event-1","payload",event,"status","APPLIED"));String bill=ReconciliationModule.buildChannelBill(a,MERCHANT,"2026-10-02");
        Map<String,Object> f=call(a,"uploadFile",map("purpose","RECON_IMPORT","_fileBytes",bill.getBytes(StandardCharsets.UTF_8)),p());Map<String,Object> body=map("merchantUid",MERCHANT,"businessDate","2026-10-02","fileId",s(f,"id"),"fileSha256",s(f,"sha256"));Map<String,Object> r=call(a,"createReconciliation",body,p()),r2=call(a,"createReconciliation",body,p());eq(s(r,"id"),s(r2,"id"),"same file identity dedupe");eq(s(r,"status"),"MATCHED","channel matches inbox");
        fails("VALIDATION_FAILED",()->call(a,"uploadFile",map("purpose","RECON_IMPORT","_fileBytes",bill.replace("\"800\"","\"801\"").getBytes(StandardCharsets.UTF_8)),p()));fails("VALIDATION_FAILED",()->call(a,"uploadFile",map("purpose","RECON_IMPORT","_fileBytes",bill.replace("SIMULATION","REAL").getBytes(StandardCharsets.UTF_8)),p()));
        fails("VALIDATION_FAILED",()->call(a,"uploadFile",map("purpose","RECON_IMPORT","_fileBytes","wrong,header\n".getBytes(StandardCharsets.UTF_8)),p()));
        fails("SCOPE_DENIED",()->call(ctx(st,"2"),"createReconciliation",body,p()));
    }
    private static void testFourWayDifferences(){
        SimState st=seed();SimContext a=ctx(st,"1"),b=ctx(st,"2");a.create("orders",map("merchantUid",MERCHANT,"status","FULFILLED","totalMinor",2000L));Map<String,Object> r=reconciliation(a,"2026-10-02");eq(s(r,"status"),"DIFFERENCE","missing AR detected");yes(bool(st.meta,"emergencyStopped"),"S0 pauses new transactions");Map<String,Object> d=a.all("differences").get(0);String ev=evidence(a);
        fails("SELF_REVIEW_DENIED",()->call(a,"resolveDifference",map("version",1L,"resolution","Synthetic correction complete","evidenceIds",Arrays.asList(ev)),p("id",s(d,"id"))));fails("UNRESOLVED_RECONCILIATION",()->call(b,"resolveDifference",map("version",1L,"resolution","Synthetic correction complete","evidenceIds",Arrays.asList(ev)),p("id",s(d,"id"))));
        // Corruption repair here is a test fixture reset; the API never allows deleting an original order.
        st.table("orders").clear();call(b,"resolveDifference",map("version",1L,"resolution","False positive fixture independently reviewed","evidenceIds",Arrays.asList(ev)),p("id",s(d,"id")));eq(s(d,"status"),"CLOSED","independent reviewed difference closes");eq(s(d,"assigneeId"),"1","assignee nonnull");eq(s(d,"checkerId"),"2","checker different nonnull");
    }
    private static void testNotificationPrivacyAndStop(){
        SimState st=seed();SimContext u=ctx(st,"4");u.notify("4","Synthetic result","orders","999");u.notify("4","Synthetic result","orders","999");eq(u.all("notifications").size(),1,"notice dedupe");Map<String,Object> page=call(u,"listNotifications",map(),p());List<Map<String,Object>> notices=list(page,"items");yes(notices.get(0).containsKey("body"),"notification exact body field");call(u,"readNotification",map(),p("id",s(notices.get(0),"id")));eq(bool(u.all("notifications").get(0),"read"),true,"mark read");fails("RESOURCE_NOT_FOUND",()->call(ctx(st,"3"),"readNotification",map(),p("id",s(notices.get(0),"id"))));
        call(u,"createPrivacyRequest",map("kind","CLOSE_ACCOUNT","reason","Synthetic rights request"),p());eq(s(u.actor(),"status"),"ACTIVE","rights request does not silently erase history");eq(u.all("privacyRequests").size(),1,"privacy request stored");call(ctx(st,"5"),"pauseSimulation",map("pauseNewTransactions",true,"reason","Synthetic exercise"),p());yes(bool(st.meta,"emergencyStopped"),"emergency stop set");
    }
    private static void testLateAllocation(){
        SimState st=seed();SimContext a=ctx(st,"1"),b=ctx(st,"2");String ev=evidence(a);Map<String,Object> rp=a.create("repayments",map("reference","late-ref","merchantUid",MERCHANT,"status","EXCEPTION","unallocatedMinor",800L,"creatorId","4"));Map<String,Object> d=a.create("differences",map("reference","late-ref","merchantUid",MERCHANT,"status","ASSIGNED","assigneeId","1","repaymentId",s(rp,"id")));
        Map<String,Object> req=M.requestLateAllocation(a,s(d,"id"),s(rp,"id"),Arrays.asList(ev));eq(n(req,"amountMinor"),800,"structured late amount frozen");fails("SELF_REVIEW_DENIED",()->M.reviewLateAllocation(a,s(req,"id"),map("version",1L,"decision","APPROVE","reason","Synthetic review","evidenceIds",Arrays.asList(ev))));a.bump(rp);fails("VERSION_CONFLICT",()->M.reviewLateAllocation(b,s(req,"id"),map("version",1L,"decision","APPROVE","reason","Synthetic review","evidenceIds",Arrays.asList(ev))));
    }
    private static Map<String,Object> debtFixture(SimContext c,long amount){
        Map<String,Object> order=c.create("orders",map("merchantUid",MERCHANT,"userUid","y00000004","userId","4","status","FULFILLED","totalMinor",amount));
        Map<String,Object> ar=c.create("receivables",map("orderId",s(order,"id"),"merchantUid",MERCHANT,"userUid","y00000004","userId","4","issuedMinor",amount,"outstandingMinor",amount,"status","OPEN","dueAt",c.now().plusSeconds(2592000).toString()));
        c.create("accounts",map("userUid","y00000004","totalMinor",10000L,"principalMinor",amount,"reservedMinor",0L,"availableMinor",10000L-amount,"blocked",false));
        FinanceModule.postJournal(c,MERCHANT,"y00000004","J-01","orders",s(order,"id"),"issue:"+s(order,"id"),c.now().toString(),Arrays.asList(FinanceModule.line("SIM_AR","DR",amount,s(ar,"id"),s(order,"id")),FinanceModule.line("SIM_SALES_CTRL","CR",amount,s(ar,"id"),s(order,"id"))));return ar;
    }
    private static void testPositiveLateAllocation(){
        SimState st=seed();SimContext a=ctx(st,"1"),b=ctx(st,"2");Map<String,Object> ar=debtFixture(a,2000);String book=FinanceModule.bookFor(a,MERCHANT),ev=evidence(a);
        Map<String,Object> rp=a.create("repayments",map("reference","late-approved-ref","merchantUid",MERCHANT,"bookId",book,"userUid","y00000004","userId","4","creatorId","4","status","EXCEPTION","amountMinor",800L,"receivedMinor",800L,"allocatedMinor",0L,"unallocatedMinor",800L,"receivableIds",Arrays.asList(s(ar,"id")),"allocations",new ArrayList<>()));
        FinanceModule.postJournal(a,MERCHANT,"y00000004","J-03","repayments",s(rp,"id"),"late-receipt",a.now().toString(),Arrays.asList(FinanceModule.line("SIM_RECEIPT","DR",800,null,null),FinanceModule.line("SIM_RETURN_PAYABLE","CR",800,null,null)));
        Map<String,Object> event=map("providerEventId","late-event","reference","late-approved-ref","eventType","PAYMENT_CONFIRMED","amountMinor",800L,"merchantUid",MERCHANT,"currency","CNY","occurredAt",a.now().toString(),"environment","SIMULATION");Map<String,Object> in=a.create("inbox",map("providerEventId","late-event","payload",event,"status","EXCEPTION"));
        Map<String,Object> d=a.create("differences",map("inboxId",s(in,"id"),"reference","late-approved-ref","merchantUid",MERCHANT,"type","LATE","severity","S1","status","OPEN","repaymentId",s(rp,"id"),"dueAt",NOW.plusSeconds(86400).toString()));Map<String,Object> recon=reconciliation(a,"2026-10-03");
        eq(n(recon,"differenceCount"),1,"orphan inbox difference attached without duplicate");eq(s(d,"assigneeId"),"1","import assigns accountable handler");
        Map<String,Object> req=M.requestLateAllocation(a,s(d,"id"),s(rp,"id"),Arrays.asList(ev));M.reviewLateAllocation(b,s(req,"id"),map("version",1L,"decision","APPROVE","reason","Independent verified reallocation","evidenceIds",Arrays.asList(ev)));
        eq(n(ar,"outstandingMinor"),1200,"J07 reduces principal once");eq(n(a.all("accounts").get(0),"availableMinor"),8800,"J07 releases allocated quota");eq(s(rp,"status"),"CONFIRMED","late receipt independently resolved");eq(a.all("journals").size(),3,"one issue one receipt one reallocation");
        long receipt=0;for(Map<String,Object> l:a.all("lines"))if("SIM_RECEIPT".equals(s(l,"accountCode")))receipt+="DR".equals(s(l,"side"))?n(l,"amountMinor"):-n(l,"amountMinor");eq(receipt,800,"J07 never records receipt again");yes(ReconciliationModule.projectionProblems(a,MERCHANT).isEmpty(),"full late flow ledger and quota rebuild consistent");
        call(b,"resolveDifference",map("version",n(d,"version"),"resolution","Independent J07 event and evidence verified","evidenceIds",Arrays.asList(ev)),p("id",s(d,"id")));eq(s(d,"status"),"CLOSED","late difference closes after structured monetary repair");eq(s(a.get("reconciliations",s(recon,"id")),"status"),"RESOLVED","reconciliation records resolved rather than matched");
    }
    private static void testNonemptyCloseAndTampering(){
        SimState st=seed();SimContext historical=new SimContext(st,"1",NOW.minusSeconds(86400));debtFixture(historical,2000);SimContext a=ctx(st,"1"),b=ctx(st,"2");String ev=evidence(a),book=FinanceModule.bookFor(a,MERCHANT);Map<String,Object> recon=reconciliation(a,"2026-10-02");eq(s(recon,"status"),"MATCHED","four-way valid nonempty journal reconciliation");Map<String,Object> day=a.all("businessDays").get(0);
        Map<String,Object> req=request(a,book,"2026-10-02",n(day,"version"),s(recon,"id"),ev,"requestBusinessDayClose");execute(b,approve(b,req,ev));yes(n(day,"highWatermark")>0,"nonempty close freezes actual sequence");Map<String,Object> snap=(Map<String,Object>)a.all("reportSnapshots").get(0).get("snapshot");eq(n(snap,"debitTotalMinor"),2000,"close snapshot carries balanced total");
        Map<String,Object> reopen=request(a,book,"2026-10-02",n(day,"version"),s(recon,"id"),ev,"requestBusinessDayReopen");Map<String,Object> approved=approve(b,reopen,ev);
        // Simulate storage corruption outside authorized domain API. It must be detected even if sums still balance.
        a.all("journals").get(0).put("tamperedAnnotation","forensic fixture");fails("UNRESOLVED_RECONCILIATION",()->execute(b,approved));
    }

}
