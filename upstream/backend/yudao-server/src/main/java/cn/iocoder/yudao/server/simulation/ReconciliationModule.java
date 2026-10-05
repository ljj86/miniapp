package cn.iocoder.yudao.server.simulation;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Simulation-only reconciliation, report controls and private artifacts. No external I/O. */
public final class ReconciliationModule implements SimModule {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final String HEADER="schema_version,environment,business_date,merchant_uid,provider_event_id,reference,event_type,amount_minor,currency,occurred_at,payload_sha256";
    private static final Set<String> OPS=new HashSet<String>(Arrays.asList("uploadFile","createReconciliation","getReconciliation","listDifferences","resolveDifference","createExport","reviewExport","getExport","getExportDownload","listNotifications","readNotification","listAuditEvents","pauseSimulation","createPrivacyRequest","listBusinessDays","requestBusinessDayClose","reviewBusinessDayRequest","executeBusinessDayRequest","requestBusinessDayReopen","requestBusinessDayReclose"));
    public boolean supports(String op){return OPS.contains(op);}
    public Object execute(String op,SimContext c,Map<String,Object> b,Map<String,String> p){
        if("uploadFile".equals(op))return upload(c,b);
        if("createReconciliation".equals(op))return reconcile(c,b);
        if("getReconciliation".equals(op)){Map<String,Object> r=c.get("reconciliations",p.get("id"));reconScope(c,r);return reconDto(c,r);}
        if("listDifferences".equals(op)){Map<String,Object> r=c.get("reconciliations",p.get("id"));reconScope(c,r);List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> d:c.all("differences"))if(s(r,"id").equals(s(d,"reconciliationId")))out.add(differenceDto(c,d));return c.page(out,p,"differences");}
        if("resolveDifference".equals(op))return resolve(c,p.get("id"),b);
        if("createExport".equals(op))return createExport(c,b);
        if("reviewExport".equals(op))return reviewExport(c,p.get("id"),b);
        if("getExport".equals(op)){Map<String,Object> e=c.get("exports",p.get("id"));exportRead(c,e);expireExport(c,e);return exportDto(c,e);}
        if("getExportDownload".equals(op))return downloadLink(c,p.get("id"));
        if("listNotifications".equals(op)){c.requireRoles("USER");List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> n:c.all("notifications"))if(c.actorId().equals(s(n,"userId")))out.add(map("id",s(n,"id"),"title",s(n,"title"),"body",n.containsKey("body")?s(n,"body"):s(n,"content"),"createdAt",s(n,"createdAt"),"read",bool(n,"read")));return c.page(out,p);}
        if("readNotification".equals(op)){c.requireRoles("USER");Map<String,Object> n=c.get("notifications",p.get("id"));c.check(c.actorId().equals(s(n,"userId")),404,"RESOURCE_NOT_FOUND","通知不可访问");if(!bool(n,"read")){n.put("read",true);n.put("readAt",c.now().toString());c.bump(n);}return ack(s(n,"id"));}
        if("listAuditEvents".equals(op)){c.requireRoles("SECURITY");List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> a:c.all("audit")){Map<String,Object> dto=c.project(a,"id","action","resourceId","createdAt");dto.put("requestId",a.get("requestId")==null?"unavailable-before-trace-fix":s(a,"requestId"));out.add(dto);}return c.page(out,p);}
        if("pauseSimulation".equals(op)){c.requireRoles("SECURITY");c.check(bool(b,"pauseNewTransactions"),422,"VALIDATION_FAILED","仅支持暂停新增模拟交易");c.state.meta.put("emergencyStopped",true);Map<String,Object> a=c.create("adminRequests",map("requestType","EMERGENCY_STOP","makerId",c.actorId(),"reason",required(c,b,"reason",1,500),"status","DONE"));c.audit("EMERGENCY_STOP","adminRequests",s(a,"id"),map("reason",s(a,"reason")));return ack(s(a,"id"));}
        if("createPrivacyRequest".equals(op)){c.requireRoles("USER");String kind=s(b,"kind");c.check(Arrays.asList("ACCESS","CORRECT","WITHDRAW","CLOSE_ACCOUNT").contains(kind),422,"VALIDATION_FAILED","未知权利请求类型");Map<String,Object> r=c.create("privacyRequests",map("userId",c.actorId(),"userUid",c.actorUid(),"kind",kind,"reason",s(b,"reason"),"status","REQUESTED","environment","SIMULATION"));c.audit("PRIVACY_REQUESTED","privacyRequests",s(r,"id"),map("kind",kind));c.notify(c.actorId(),"个人信息请求已受理","privacyRequests",s(r,"id"));return ack(s(r,"id"));}
        if("listBusinessDays".equals(op))return listDays(c,p);
        if("requestBusinessDayClose".equals(op))return requestDay(c,b,p,"CLOSE");
        if("requestBusinessDayReopen".equals(op))return requestDay(c,b,p,"REOPEN_REVIEW");
        if("requestBusinessDayReclose".equals(op))return requestDay(c,b,p,"RECLOSE");
        if("reviewBusinessDayRequest".equals(op))return reviewDay(c,p.get("id"),b);
        if("executeBusinessDayRequest".equals(op))return executeDay(c,p.get("id"),b);
        throw new IllegalArgumentException("Unsupported operation "+op);
    }
    private static Map<String,Object> ack(String id){return map("accepted",true,"resourceId",id);}
    private static String required(SimContext c,Map<String,Object> b,String key,int min,int max){String v=s(b,key);c.check(v!=null && v.trim().length()>=min && v.length()<=max,422,"VALIDATION_FAILED",key+"长度不符合要求");return v;}
    private static LocalDate date(SimContext c,String v){try{return LocalDate.parse(v);}catch(Exception e){throw new SimException(422,"VALIDATION_FAILED","日期格式无效");}}
    private static String sha(byte[] bytes){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte x:d)out.append(String.format("%02x",x));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private Object upload(SimContext c,Map<String,Object> b){
        c.requireRoles("USER","OWNER","LEDGER_MAKER");String purpose=s(b,"purpose");c.check(Arrays.asList("SIM_EVIDENCE","RECON_IMPORT").contains(purpose),422,"VALIDATION_FAILED","文件用途不允许");
        byte[] bytes=null;Object raw=b.get("_fileBytes");if(raw instanceof byte[])bytes=(byte[])raw;else if(b.get("file") instanceof String)bytes=s(b,"file").getBytes(StandardCharsets.UTF_8);
        c.check(bytes!=null && bytes.length>0 && bytes.length<=10485760,422,"VALIDATION_FAILED","私有文件必须为1至10MiB以内");String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new SimException(422,"VALIDATION_FAILED","模拟证据仅接受UTF-8文本/CSV，不接收真实证件");}
        c.check(text.indexOf('\0')<0,422,"VALIDATION_FAILED","文件包含不允许的二进制内容");
        if("RECON_IMPORT".equals(purpose))parseCsv(c,text); // strict format validation, even before a job references it
        Map<String,Object> f=c.create("files",map("ownerId",c.actorId(),"purpose",purpose,"status","READY","sha256",sha(bytes),"sizeBytes",bytes.length,"contentBase64",Base64.getEncoder().encodeToString(bytes),"private",true,"environment","SIMULATION","scanPolicy","SYNTHETIC_UTF8_ONLY"));
        c.audit("FILE_UPLOADED","files",s(f,"id"),map("purpose",purpose,"sha256",s(f,"sha256"),"sizeBytes",bytes.length));return fileDto(c,f);
    }
    private static Map<String,Object> fileDto(SimContext c,Map<String,Object> f){return c.project(f,"id","status","sha256","sizeBytes");}
    private static void evidence(SimContext c,List<String> ids,String makerId,boolean nonempty){
        c.check(!nonempty || !ids.isEmpty(),422,"VALIDATION_FAILED","需要可复核的合成证据文件");
        for(String id:ids){Map<String,Object> f=c.get("files",id);c.check("READY".equals(s(f,"status")) && "SIM_EVIDENCE".equals(s(f,"purpose")),422,"VALIDATION_FAILED","证据文件未就绪或用途错误");c.check(c.actorId().equals(s(f,"ownerId")) || Objects.equals(makerId,s(f,"ownerId")),403,"SCOPE_DENIED","证据文件不属于当前申请/复核范围");}
    }
    private static void reconScope(SimContext c,Map<String,Object> r){c.requireScope(s(r,"merchantUid"),null,null,"LEDGER_MAKER","LEDGER_CHECKER");}
    private static Map<String,Object> reconDto(SimContext c,Map<String,Object> r){return c.project(r,"id","status","businessDate","differenceCount","version");}
    private static Map<String,Object> differenceDto(SimContext c,Map<String,Object> d){return c.project(d,"version","id","type","severity","status","dueAt","reference");}
    private Object reconcile(SimContext c,Map<String,Object> b){
        String merchant=required(c,b,"merchantUid",8,8),business=date(c,s(b,"businessDate")).toString();c.requireScope(merchant,null,null,"LEDGER_MAKER");
        Map<String,Object> f=c.get("files",s(b,"fileId"));c.check(c.actorId().equals(s(f,"ownerId")),403,"SCOPE_DENIED","对账文件不是当前申请人上传");
        c.check("READY".equals(s(f,"status")) && "RECON_IMPORT".equals(s(f,"purpose")),422,"VALIDATION_FAILED","文件未就绪或用途不是对账");c.check(Objects.equals(s(b,"fileSha256"),s(f,"sha256")),422,"VALIDATION_FAILED","文件摘要不匹配");
        for(Map<String,Object> r:c.all("reconciliations"))if(merchant.equals(s(r,"merchantUid")) && business.equals(s(r,"businessDate")) && s(f,"sha256").equals(s(r,"fileHash")))return reconDto(c,r);
        String content=new String(Base64.getDecoder().decode(s(f,"contentBase64")),StandardCharsets.UTF_8);List<Map<String,Object>> rows=parseCsv(c,content);
        Map<String,Object> r=c.create("reconciliations",map("merchantUid",merchant,"businessDate",business,"fileId",s(f,"id"),"fileHash",s(f,"sha256"),"makerId",c.actorId(),"status","RUNNING","differenceCount",0L,"rowCount",rows.size(),"environment","SIMULATION"));
        for(Map<String,Object> existing:c.all("differences"))if(existing.get("reconciliationId")==null && merchant.equals(s(existing,"merchantUid")) && !"CLOSED".equals(s(existing,"status"))){Map<String,Object> original=existing.get("inboxId")==null?null:c.get("inbox",s(existing,"inboxId"));if(original!=null && business.equals(occurrenceDate(payload(original)))){existing.put("reconciliationId",s(r,"id"));existing.put("assigneeId",c.actorId());existing.put("status","ASSIGNED");setSla(c,existing);c.bump(existing);r.put("differenceCount",n(r,"differenceCount")+1);}}
        Set<String> seen=new HashSet<>();long total=0;
        for(Map<String,Object> row:rows){
            String event=s(row,"providerEventId"),ref=s(row,"reference");total=Math.addExact(total,n(row,"amountMinor"));
            if(!merchant.equals(s(row,"merchantUid")) || !business.equals(s(row,"businessDate"))){difference(c,r,"SCOPE","S0",ref,"账单商家/业务日不在本批次范围",null);continue;}
            if(!seen.add(event)){difference(c,r,"DUPLICATE","S1",ref,"渠道文件事件重复",null);continue;}
            Map<String,Object> local=find(c,"inbox","providerEventId",event);
            if(local==null){difference(c,r,"MISSING_LOCAL","S1",ref,"渠道有事件，本地未核实",null);continue;}
            Map<String,Object> payload=payload(local);String localMerchant=s(payload,"merchantUid");
            if(!merchant.equals(localMerchant)){difference(c,r,"SCOPE","S0",ref,"入站事件商家不匹配",s(local,"id"));continue;}
            if(!ref.equals(s(payload,"reference")) || n(row,"amountMinor")!=n(payload,"amountMinor") || !Objects.equals(s(row,"eventType"),s(payload,"eventType"))){difference(c,r,"AMOUNT","S1",ref,"事件业务号/类型/金额不一致",s(local,"id"));continue;}
            String expected=eventHash(c,payload);
            if(!s(row,"payloadSha256").equals(expected)){difference(c,r,"AMOUNT","S1",ref,"渠道与本地事件字段摘要不一致",s(local,"id"));continue;}
            Map<String,Object> repayment=find(c,"repayments","reference",ref);
            if(repayment!=null && "EXCEPTION".equals(s(repayment,"status")) || repayment==null && "EXCEPTION".equals(s(local,"status"))){Map<String,Object> d=difference(c,r,"LATE","S1",ref,"迟到成功待独立分配/模拟退回",s(local,"id"));if(repayment!=null)d.put("repaymentId",s(repayment,"id"));}
        }
        for(Map<String,Object> in:c.all("inbox")){Map<String,Object> payload=payload(in);if(!merchant.equals(s(payload,"merchantUid")) || !business.equals(occurrenceDate(payload)))continue;if(!seen.contains(s(payload,"providerEventId")))difference(c,r,"MISSING_CHANNEL","S1",s(payload,"reference"),"本地事件缺渠道账单",s(in,"id"));}
        r.put("totalAmountMinor",total);List<String> structural=projectionProblems(c,merchant);
        for(String problem:structural)difference(c,r,"AMOUNT","S0","internal:"+s(r,"id"),problem,null);
        r.put("fourWayResults",map("orderReceivable",!contains(structural,"ORDER:"),"quotaProjection",!contains(structural,"QUOTA:"),"journalProjection",!contains(structural,"JOURNAL:"),"channel",n(r,"differenceCount")==0));
        r.put("status",n(r,"differenceCount")==0?"MATCHED":"DIFFERENCE");c.bump(r);c.audit("RECONCILIATION_COMPLETED","reconciliations",s(r,"id"),map("differenceCount",n(r,"differenceCount"),"fileHash",s(f,"sha256")));return reconDto(c,r);
    }
    private static boolean contains(List<String> values,String prefix){for(String s:values)if(s.startsWith(prefix))return true;return false;}
    @SuppressWarnings("unchecked") private static Map<String,Object> payload(Map<String,Object> in){Object p=in.get("payload");if(!(p instanceof Map))p=in.get("body");return p instanceof Map?(Map<String,Object>)p:in;}
    private static String occurrenceDate(Map<String,Object> p){try{return Instant.parse(s(p,"occurredAt")).atZone(ZONE).toLocalDate().toString();}catch(Exception e){return "INVALID";}}
    private static Map<String,Object> find(SimContext c,String kind,String key,String value){for(Map<String,Object> r:c.all(kind))if(Objects.equals(value,s(r,key)))return r;return null;}
    private static Map<String,Object> difference(SimContext c,Map<String,Object> recon,String type,String severity,String ref,String reason,String inboxId){
        for(Map<String,Object> existing:c.all("differences"))if(s(recon,"id").equals(s(existing,"reconciliationId")) && Objects.equals(ref,s(existing,"reference")) && type.equals(s(existing,"type")))return existing;
        long seconds="S0".equals(severity)?7200:"S1".equals(severity)?86400:259200;
        Map<String,Object> d=c.create("differences",map("reconciliationId",s(recon,"id"),"merchantUid",s(recon,"merchantUid"),"type",type,"severity",severity,"status","ASSIGNED","assigneeId",s(recon,"makerId"),"checkerId",null,"dueAt",c.now().plusSeconds(seconds).toString(),"ackDueAt",c.now().plusSeconds("S0".equals(severity)?900:"S1".equals(severity)?14400:86400).toString(),"reference",ref,"reason",reason,"inboxId",inboxId,"slaPolicy","CANDIDATE_ELAPSED_TIME_FIXTURE; production working-calendar review pending"));
        setSla(c,d);recon.put("differenceCount",n(recon,"differenceCount")+1);if("S0".equals(severity))c.state.meta.put("emergencyStopped",true);return d;
    }
    private Object resolve(SimContext c,String id,Map<String,Object> b){
        Map<String,Object> d=c.get("differences",id),r=c.get("reconciliations",s(d,"reconciliationId"));c.requireScope(s(r,"merchantUid"),null,null,"LEDGER_CHECKER");c.version(d,b);c.independent(s(d,"assigneeId"));
        c.check(!"CLOSED".equals(s(d,"status")),409,"STATE_CONFLICT","差异已关闭");required(c,b,"resolution",5,1000);evidence(c,SimContext.<String>list(b,"evidenceIds"),s(d,"assigneeId"),true);
        // Closing is evidence-only. Monetary repair must already exist as a separate reviewed event.
        String ref=s(d,"reference");Map<String,Object> rp=find(c,"repayments","reference",ref);
        c.check(rp==null || !"EXCEPTION".equals(s(rp,"status")),409,"UNRESOLVED_RECONCILIATION","迟到款尚未通过独立重分配/模拟退回处理");
        c.check(projectionProblems(c,s(r,"merchantUid")).isEmpty(),409,"UNRESOLVED_RECONCILIATION","内部金额/额度/分录差异仍存在");
        d.put("checkerId",c.actorId());d.put("resolution",s(b,"resolution"));d.put("evidenceIds",new ArrayList<>(list(b,"evidenceIds")));d.put("status","CLOSED");d.put("closedAt",c.now().toString());c.bump(d);
        boolean all=true;for(Map<String,Object> x:c.all("differences"))if(s(r,"id").equals(s(x,"reconciliationId")) && !"CLOSED".equals(s(x,"status")))all=false;
        if(all){r.put("status","RESOLVED");c.bump(r);}c.audit("DIFFERENCE_INDEPENDENTLY_CLOSED","differences",id,map("assigneeId",s(d,"assigneeId"),"checkerId",c.actorId(),"evidenceIds",list(b,"evidenceIds")));return differenceDto(c,d);
    }
    public static List<String> projectionProblems(SimContext c,String merchant){
        List<String> errors=new ArrayList<>();Set<String> merchantUsers=new HashSet<>();
        for(Map<String,Object> o:c.all("orders"))if(merchant.equals(s(o,"merchantUid")) && "FULFILLED".equals(s(o,"status"))){int count=0;for(Map<String,Object> ar:c.all("receivables"))if(s(o,"id").equals(s(ar,"orderId"))){count++;if(n(ar,"issuedMinor")!=n(o,"totalMinor"))errors.add("ORDER:订单与应收原始金额不一致:"+s(o,"id"));}if(count!=1)errors.add("ORDER:履约订单应有且仅有一笔应收:"+s(o,"id"));}
        for(Map<String,Object> ar:c.all("receivables"))if(merchant.equals(s(ar,"merchantUid"))){merchantUsers.add(s(ar,"userUid"));if(n(ar,"outstandingMinor")<0 || n(ar,"outstandingMinor")>n(ar,"issuedMinor"))errors.add("ORDER:应收本金范围错误:"+s(ar,"id"));}
        for(String uid:merchantUsers){long principal=0,reserved=0;for(Map<String,Object> ar:c.all("receivables"))if(uid.equals(s(ar,"userUid")))principal+=n(ar,"outstandingMinor");for(Map<String,Object> hold:c.all("reservations"))if(uid.equals(s(hold,"userUid")) && "HELD".equals(s(hold,"status")))reserved+=n(hold,"amountMinor");Map<String,Object> account=find(c,"accounts","userUid",uid);if(account==null || n(account,"principalMinor")!=principal || n(account,"reservedMinor")!=reserved || n(account,"totalMinor")-principal-reserved!=n(account,"availableMinor") || principal+reserved>n(account,"totalMinor"))errors.add("QUOTA:全平台本金/预占投影不一致:"+uid);}
        long arLedger=0,arProjection=0;Set<String> eventKeys=new HashSet<>();
        for(Map<String,Object> j:c.all("journals"))if(merchant.equals(s(j,"merchantUid"))){long dr=0,cr=0;int count=0;String key=s(j,"bookId")+":"+s(j,"eventKey");if(!eventKeys.add(key))errors.add("JOURNAL:重复事件:"+key);for(Map<String,Object> line:c.all("lines"))if(s(j,"id").equals(s(line,"journalId"))){count++;long amount=n(line,"amountMinor");if(amount<=0)errors.add("JOURNAL:分录金额必须为正:"+s(j,"id"));if("DR".equals(s(line,"side")))dr+=amount;else if("CR".equals(s(line,"side")))cr+=amount;else errors.add("JOURNAL:分录方向无效:"+s(j,"id"));if("SIM_AR".equals(s(line,"accountCode")))arLedger+="DR".equals(s(line,"side"))?amount:-amount;}if(count<2 || dr<=0 || dr!=cr || dr!=n(j,"debitTotalMinor") || cr!=n(j,"creditTotalMinor"))errors.add("JOURNAL:借贷/汇总不平衡:"+s(j,"id"));}
        for(Map<String,Object> ar:c.all("receivables"))if(merchant.equals(s(ar,"merchantUid")))arProjection+=n(ar,"outstandingMinor");if(arLedger!=arProjection)errors.add("JOURNAL:应收科目与本金投影不一致");return errors;
    }
    private Object listDays(SimContext c,Map<String,String> p){
        Map<String,Object> book=c.get("books",p.get("bookId"));c.requireScope(s(book,"merchantUid"),null,s(book,"id"),"LEDGER_MAKER","LEDGER_CHECKER","SECURITY");
        int page=positive(c,p.get("page"),1),size=positive(c,p.get("pageSize"),20);c.check(size<=100,422,"VALIDATION_FAILED","pageSize不能超过100");List<Map<String,Object>> rows=new ArrayList<>();
        for(Map<String,Object> d:c.all("businessDays"))if(s(book,"id").equals(s(d,"bookId")) && (p.get("status")==null || p.get("status").equals(s(d,"status"))))rows.add(dayDto(c,d));
        boolean asc="businessDate:asc".equals(p.get("sort"));rows.sort((x,y)->asc?s(x,"businessDate").compareTo(s(y,"businessDate")):s(y,"businessDate").compareTo(s(x,"businessDate")));
        long offset=(long)(page-1)*size;int from=(int)Math.min(rows.size(),offset),to=Math.min(rows.size(),from+size);return map("items",new ArrayList<>(rows.subList(from,to)),"page",page,"pageSize",size,"total",rows.size());
    }
    private static int positive(SimContext c,String value,int fallback){try{int v=value==null?fallback:Integer.parseInt(value);c.check(v>0,422,"VALIDATION_FAILED","分页参数必须为正数");return v;}catch(NumberFormatException e){throw new SimException(422,"VALIDATION_FAILED","分页参数不是整数");}}
    private static Map<String,Object> dayDto(SimContext c,Map<String,Object> d){
        Map<String,Object> out=c.project(d,"id","bookId","businessDate","status","version","reportRevision","cutoffAt","highWatermark","environment");out.put("snapshotHash",d.get("snapshotHash"));out.put("closedAt",d.get("closedAt"));return out;
    }
    private static Map<String,Object> dayRequestDto(SimContext c,Map<String,Object> r){Map<String,Object> out=c.project(r,"id","dayId","action","status","makerId","version","reason","expiresAt","environment");out.put("checkerId",r.get("checkerId"));return out;}
    private static Map<String,Object> day(SimContext c,String bookId,String value){
        for(Map<String,Object> d:c.all("businessDays"))if(bookId.equals(s(d,"bookId")) && value.equals(s(d,"businessDate")))return d;
        // Empty historical days can be explicitly closed; their report has no journals.
        LocalDate date=date(c,value);c.check(!date.isAfter(c.now().atZone(ZONE).toLocalDate()),422,"VALIDATION_FAILED","不能创建未来业务日");
        return c.create("businessDays",map("bookId",bookId,"businessDate",value,"status","OPEN","reportRevision",1L,"cutoffAt",date.plusDays(1).atStartOfDay(ZONE).toInstant().toString(),"highWatermark","0","snapshotHash",null,"closedAt",null,"environment","SIMULATION"));
    }
    private Object requestDay(SimContext c,Map<String,Object> b,Map<String,String> p,String action){
        Map<String,Object> book=c.get("books",p.get("bookId"));c.requireScope(s(book,"merchantUid"),null,s(book,"id"),"LEDGER_MAKER");Map<String,Object> d=day(c,s(book,"id"),date(c,p.get("businessDate")).toString());
        c.check(n(d,"version")==n(b,"expectedDayVersion"),409,"DAY_VERSION_CONFLICT","业务日已更新");validateDayAction(c,d,action);evidence(c,SimContext.<String>list(b,"evidenceIds"),c.actorId(),true);checkRecons(c,d,s(book,"merchantUid"),SimContext.<String>list(b,"reconciliationIds"));
        String reason=required(c,b,"reason",10,1000),impact=required(c,b,"impactSummary",10,2000);
        for(Map<String,Object> r:c.all("dayRequests"))if(s(d,"id").equals(s(r,"dayId")) && action.equals(s(r,"action")) && n(d,"version")==n(r,"expectedDayVersion") && Arrays.asList("REQUESTED","APPROVED","EXECUTING").contains(s(r,"status")))throw new SimException(409,"STATE_CONFLICT","同一版本已有未结束业务日申请");
        Map<String,Object> r=c.create("dayRequests",map("dayId",s(d,"id"),"bookId",s(book,"id"),"merchantUid",s(book,"merchantUid"),"action",action,"status","REQUESTED","makerId",c.actorId(),"checkerId",null,"expectedDayVersion",n(d,"version"),"reason",reason,"impactSummary",impact,"evidenceIds",new ArrayList<>(list(b,"evidenceIds")),"reconciliationIds",new ArrayList<>(list(b,"reconciliationIds")),"expiresAt",c.now().plusSeconds(86400).toString(),"originalSnapshotHash",d.get("snapshotHash"),"originalJournalHash",historicalJournalHash(c,d),"environment","SIMULATION","approvalPolicy","INDEPENDENT_SYNTHETIC_WORKFLOW_NOT_BUSINESS_SIGNOFF"));
        c.audit("BUSINESS_DAY_REQUESTED","dayRequests",s(r,"id"),map("dayId",s(d,"id"),"action",action));return dayRequestDto(c,r);
    }
    private static void validateDayAction(SimContext c,Map<String,Object> d,String action){
        String expected="CLOSE".equals(action)?"OPEN":"REOPEN_REVIEW".equals(action)?"CLOSED":"REOPENED_REVIEW";
        c.check(expected.equals(s(d,"status")),409,"STATE_CONFLICT","业务日当前状态不支持该动作");
        if("CLOSE".equals(action))c.check(!c.now().isBefore(Instant.parse(s(d,"cutoffAt"))),409,"BUSINESS_DAY_NOT_WRITABLE","未到业务日截止，不可提前关账");
    }
    private static void checkRecons(SimContext c,Map<String,Object> d,String merchant,List<String> ids){
        c.check(!ids.isEmpty() && ids.size()<=20 && new HashSet<>(ids).size()==ids.size(),422,"VALIDATION_FAILED","需要1至20个不重复对账结果");
        for(String id:ids){Map<String,Object> r=c.get("reconciliations",id);c.check(merchant.equals(s(r,"merchantUid")) && s(d,"businessDate").equals(s(r,"businessDate")),403,"SCOPE_DENIED","对账结果不属于当前商家业务日");c.check(Arrays.asList("MATCHED","RESOLVED").contains(s(r,"status")),409,"UNRESOLVED_RECONCILIATION","对账尚有未解决差异");}
        for(Map<String,Object> r:c.all("reconciliations"))if(merchant.equals(s(r,"merchantUid")) && s(d,"businessDate").equals(s(r,"businessDate")))for(Map<String,Object> x:c.all("differences"))if(s(r,"id").equals(s(x,"reconciliationId")) && !"CLOSED".equals(s(x,"status")))throw new SimException(409,"UNRESOLVED_RECONCILIATION","本业务日仍有未关闭差异");
        for(Map<String,Object> x:c.all("differences"))if(merchant.equals(s(x,"merchantUid")) && x.get("reconciliationId")==null && !"CLOSED".equals(s(x,"status"))){Map<String,Object> in=x.get("inboxId")==null?null:c.get("inbox",s(x,"inboxId"));if(in==null || s(d,"businessDate").equals(occurrenceDate(payload(in))))throw new SimException(409,"UNRESOLVED_RECONCILIATION","存在尚未纳入对账的入站异常");}
        c.check(projectionProblems(c,merchant).isEmpty(),409,"UNRESOLVED_RECONCILIATION","订单/额度/账本校验未通过");
    }
    private static void validRequest(SimContext c,Map<String,Object> r){c.check(c.now().isBefore(Instant.parse(s(r,"expiresAt"))),410,"REQUEST_EXPIRED","业务日申请已过24小时候选有效期，请重新申请");}
    private Object reviewDay(SimContext c,String id,Map<String,Object> b){
        Map<String,Object> r=c.get("dayRequests",id),d=c.get("businessDays",s(r,"dayId"));c.requireScope(s(r,"merchantUid"),null,s(r,"bookId"),"LEDGER_CHECKER");c.version(r,b);c.independent(s(r,"makerId"));validRequest(c,r);
        c.check("REQUESTED".equals(s(r,"status")),409,"STATE_CONFLICT","申请已完成复核");c.check(n(d,"version")==n(r,"expectedDayVersion"),409,"DAY_VERSION_CONFLICT","业务日版本已变化");required(c,b,"reason",5,1000);evidence(c,SimContext.<String>list(b,"evidenceIds"),s(r,"makerId"),true);
        String decision=s(b,"decision");c.check(Arrays.asList("APPROVE","REJECT").contains(decision),422,"VALIDATION_FAILED","未知复核决定");
        if("APPROVE".equals(decision)){validateDayAction(c,d,s(r,"action"));checkRecons(c,d,s(r,"merchantUid"),SimContext.<String>list(r,"reconciliationIds"));}
        r.put("checkerId",c.actorId());r.put("decisionReason",s(b,"reason"));r.put("decisionEvidenceIds",new ArrayList<>(list(b,"evidenceIds")));r.put("status","APPROVE".equals(decision)?"APPROVED":"REJECTED");c.bump(r);c.audit("BUSINESS_DAY_REVIEWED","dayRequests",id,map("decision",decision,"makerId",s(r,"makerId"),"checkerId",c.actorId()));return dayRequestDto(c,r);
    }
    private Object executeDay(SimContext c,String id,Map<String,Object> b){
        Map<String,Object> r=c.get("dayRequests",id),d=c.get("businessDays",s(r,"dayId"));c.requireScope(s(r,"merchantUid"),null,s(r,"bookId"),"LEDGER_CHECKER");c.version(r,b);c.independent(s(r,"makerId"));validRequest(c,r);
        c.check("APPROVED".equals(s(r,"status")) && r.get("checkerId")!=null && !s(r,"checkerId").equals(s(r,"makerId")),409,"STATE_CONFLICT","只有经过独立批准的申请可执行");
        c.check(n(d,"version")==n(r,"expectedDayVersion"),409,"DAY_VERSION_CONFLICT","批准后业务日已变化");String action=s(r,"action");validateDayAction(c,d,action);checkRecons(c,d,s(r,"merchantUid"),SimContext.<String>list(r,"reconciliationIds"));
        if(!"CLOSE".equals(action)){c.check(Objects.equals(d.get("snapshotHash"),r.get("originalSnapshotHash")),409,"DAY_VERSION_CONFLICT","原报告摘要已变化");c.check(Objects.equals(historicalJournalHash(c,d),s(r,"originalJournalHash")),409,"UNRESOLVED_RECONCILIATION","关闭日历史分录发生变化");}
        r.put("status","EXECUTING");c.bump(r);String oldHash=s(d,"snapshotHash");
        if("REOPEN_REVIEW".equals(action)){d.put("status","REOPENED_REVIEW");c.bump(d);r.put("resultSummary",map("oldHash",oldHash,"newHash",oldHash,"journalHash",historicalJournalHash(c,d),"mode","REPORT_REVIEW_ONLY"));}
        else {
            if("CLOSE".equals(action))d.put("status","CLOSING");long revision=n(d,"reportRevision");if("RECLOSE".equals(action))revision++;
            List<Map<String,Object>> selected=journalsForDay(c,d);long high=0,debit=0,credit=0;for(Map<String,Object> j:selected){high=Math.max(high,n(j,"sequenceNo"));debit+=n(j,"debitTotalMinor");credit+=n(j,"creditTotalMinor");}
            Map<String,Object> snapshot=map("bookId",s(d,"bookId"),"businessDate",s(d,"businessDate"),"reportRevision",revision,"highWatermark",Long.toString(high),"journalCount",selected.size(),"debitTotalMinor",debit,"creditTotalMinor",credit,"journalHash",historicalJournalHash(c,d),"previousHash",oldHash,"reconciliationIds",new ArrayList<>(list(r,"reconciliationIds")),"evidenceIds",new ArrayList<>(list(r,"decisionEvidenceIds")),"requestId",s(r,"id"),"environment","SIMULATION");
            String hash=c.hashObject(snapshot);c.create("reportSnapshots",map("dayId",s(d,"id"),"revision",revision,"snapshot",snapshot,"snapshotHash",hash,"previousHash",oldHash,"immutable",true));
            d.put("status","CLOSED");d.put("reportRevision",revision);d.put("highWatermark",Long.toString(high));d.put("snapshotHash",hash);d.put("closedAt",c.now().toString());c.bump(d);r.put("resultSummary",map("oldHash",oldHash,"newHash",hash,"reportRevision",revision,"journalHash",historicalJournalHash(c,d)));
        }
        r.put("status","EXECUTED");r.put("executedAt",c.now().toString());r.put("executorId",c.actorId());c.bump(r);c.audit("BUSINESS_DAY_"+action,"dayRequests",id,map("dayId",s(d,"id"),"result",r.get("resultSummary")));c.notify(s(r,"makerId"),"模拟业务日报告操作已完成","dayRequests",id);return dayRequestDto(c,r);
    }
    private static List<Map<String,Object>> journalsForDay(SimContext c,Map<String,Object> d){List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> j:c.all("journals"))if(s(d,"bookId").equals(s(j,"bookId")) && s(d,"businessDate").equals(s(j,"postingDate")))out.add(j);out.sort(Comparator.comparingLong(j->n(j,"sequenceNo")));return out;}
    private static String historicalJournalHash(SimContext c,Map<String,Object> d){List<Map<String,Object>> values=new ArrayList<>();for(Map<String,Object> j:journalsForDay(c,d)){List<Map<String,Object>> lines=new ArrayList<>();for(Map<String,Object> l:c.all("lines"))if(s(j,"id").equals(s(l,"journalId")))lines.add(l);values.add(map("journal",j,"lines",lines));}return c.hashObject(values);}
    private Object createExport(SimContext c,Map<String,Object> b){
        String merchant=s(b,"merchantUid");c.requireScope(merchant,null,null,"OWNER","LEDGER_MAKER");LocalDate from=date(c,s(b,"dateFrom")),to=date(c,s(b,"dateTo"));c.check(!to.isBefore(from) && !to.isAfter(from.plusDays(366)),422,"VALIDATION_FAILED","导出区间须在366天内");
        Map<String,Object> e=c.create("exports",map("merchantUid",merchant,"requesterId",c.actorId(),"checkerId",null,"dateFrom",from.toString(),"dateTo",to.toString(),"purpose",required(c,b,"purpose",5,500),"status","REQUESTED","environment","SIMULATION"));c.audit("EXPORT_REQUESTED","exports",s(e,"id"),map("merchantUid",merchant,"dateFrom",from.toString(),"dateTo",to.toString()));return exportDto(c,e);
    }
    private Object reviewExport(SimContext c,String id,Map<String,Object> b){
        Map<String,Object> e=c.get("exports",id);c.requireScope(s(e,"merchantUid"),null,null,"LEDGER_CHECKER");c.version(e,b);c.independent(s(e,"requesterId"));c.check("REQUESTED".equals(s(e,"status")),409,"STATE_CONFLICT","导出申请已审批");required(c,b,"reason",5,500);evidence(c,SimContext.<String>list(b,"evidenceIds"),s(e,"requesterId"),true);
        String decision=s(b,"decision");c.check(Arrays.asList("APPROVE","REJECT").contains(decision),422,"VALIDATION_FAILED","未知审批决定");e.put("checkerId",c.actorId());e.put("decisionReason",s(b,"reason"));e.put("evidenceIds",new ArrayList<>(list(b,"evidenceIds")));e.put("approvedAt",c.now().toString());
        if("REJECT".equals(decision)){e.put("status","REJECTED");c.bump(e);return exportDto(c,e);}
        e.put("status","RUNNING");List<Map<String,Object>> rows=new ArrayList<>();for(Map<String,Object> ar:c.all("receivables"))if(s(e,"merchantUid").equals(s(ar,"merchantUid"))){String created=Instant.parse(s(ar,"createdAt")).atZone(ZONE).toLocalDate().toString();if(created.compareTo(s(e,"dateFrom"))>=0 && created.compareTo(s(e,"dateTo"))<=0)rows.add(ar);}
        c.check(rows.size()<=10000,422,"VALIDATION_FAILED","单次导出最多10000行，请缩小区间");rows.sort(Comparator.comparingLong(x->n(x,"id")));
        StringBuilder csv=new StringBuilder("watermark,export_id,recipient_uid,merchant_uid,receivable_id,order_id,user_uid,issued_minor,outstanding_minor,status,due_at\n");String recipient=s(c.get("users",s(e,"requesterId")),"uid");
        for(Map<String,Object> ar:rows)csv.append(csvLine(Arrays.asList("SIMULATED_NOT_LEGAL_ACCOUNTING",id,recipient,s(e,"merchantUid"),s(ar,"id"),s(ar,"orderId"),s(ar,"userUid"),Long.toString(n(ar,"issuedMinor")),Long.toString(n(ar,"outstandingMinor")),s(ar,"status"),s(ar,"dueAt"))));
        // Even an empty report includes recipient/task watermark metadata, not merely an unlabelled header.
        if(rows.isEmpty())csv.append(csvLine(Arrays.asList("SIMULATED_EMPTY_REPORT",id,recipient,s(e,"merchantUid"),"","","","0","0","EMPTY","")));
        byte[] bytes=csv.toString().getBytes(StandardCharsets.UTF_8);Map<String,Object> f=c.create("files",map("ownerId",s(e,"requesterId"),"purpose","PRIVATE_EXPORT","status","READY","sha256",sha(bytes),"sizeBytes",bytes.length,"contentBase64",Base64.getEncoder().encodeToString(bytes),"private",true,"environment","SIMULATION"));
        e.put("fileId",s(f,"id"));e.put("status","READY");e.put("rowCount",rows.size());e.put("expiresAt",c.now().plusSeconds(3600).toString());c.bump(e);c.audit("EXPORT_INDEPENDENTLY_APPROVED","exports",id,map("requesterId",s(e,"requesterId"),"checkerId",c.actorId(),"sha256",s(f,"sha256"),"rowCount",rows.size()));c.notify(s(e,"requesterId"),"模拟账单导出已就绪","exports",id);return exportDto(c,e);
    }
    private static Map<String,Object> exportDto(SimContext c,Map<String,Object> e){return c.project(e,"id","status","version","expiresAt");}
    private static void exportRead(SimContext c,Map<String,Object> e){c.requireScope(s(e,"merchantUid"),null,null,"OWNER","LEDGER_MAKER");c.check(c.actorId().equals(s(e,"requesterId")),404,"RESOURCE_NOT_FOUND","导出只对原申请人开放");}
    private static void expireExport(SimContext c,Map<String,Object> e){if("READY".equals(s(e,"status")) && !c.now().isBefore(Instant.parse(s(e,"expiresAt")))){e.put("status","EXPIRED");c.bump(e);}}
    private Object downloadLink(SimContext c,String id){
        Map<String,Object> e=c.get("exports",id);exportRead(c,e);expireExport(c,e);c.check("READY".equals(s(e,"status")),410,"CREDENTIAL_EXPIRED","导出文件未就绪或已到期");
        String token=c.token();Instant expires=c.now().plusSeconds(300);Instant fileExpiry=Instant.parse(s(e,"expiresAt"));if(fileExpiry.isBefore(expires))expires=fileExpiry;e.put("downloadTokenHash",c.hash(token));e.put("downloadPrincipalId",c.actorId());e.put("downloadExpiresAt",expires.toString());
        String base=str(c.state.meta.get("publicBaseUrl"));if(base==null)base="http://127.0.0.1:48080";while(base.endsWith("/"))base=base.substring(0,base.length()-1);
        c.audit("EXPORT_DOWNLOAD_LINK_ISSUED","exports",id,map("recipientId",c.actorId(),"expiresAt",expires.toString()));return map("url",base+"/api/v1/exports/"+id+"/content?token="+token,"expiresAt",expires.toString());
    }
    /** Additional transport endpoint must keep Bearer authentication; the token alone is insufficient. */
    public byte[] downloadExport(SimContext c,String exportId,String token){
        Map<String,Object> e=c.get("exports",exportId);exportRead(c,e);expireExport(c,e);c.check("READY".equals(s(e,"status")),410,"CREDENTIAL_EXPIRED","导出文件已到期");
        c.check(token!=null && e.get("downloadTokenHash")!=null && MessageDigest.isEqual(c.hash(token).getBytes(StandardCharsets.UTF_8),s(e,"downloadTokenHash").getBytes(StandardCharsets.UTF_8)) && c.actorId().equals(s(e,"downloadPrincipalId")),403,"SCOPE_DENIED","下载凭证无效或领取人不一致");c.check(c.now().isBefore(Instant.parse(s(e,"downloadExpiresAt"))),410,"CREDENTIAL_EXPIRED","下载链接已到期");
        Map<String,Object> f=c.get("files",s(e,"fileId"));byte[] bytes=Base64.getDecoder().decode(s(f,"contentBase64"));c.check(sha(bytes).equals(s(f,"sha256")),409,"STATE_CONFLICT","导出完整性校验失败");c.audit("EXPORT_DOWNLOADED","exports",exportId,map("recipientId",c.actorId(),"sha256",s(f,"sha256")));return bytes;
    }
    /** Supplemental structured request; no arbitrary amount is taken from resolution text. */
    public Map<String,Object> requestLateAllocation(SimContext c,String differenceId,String repaymentId,List<String> evidenceIds){
        Map<String,Object> d=c.get("differences",differenceId);c.requireScope(s(d,"merchantUid"),null,null,"LEDGER_MAKER");c.check(c.actorId().equals(s(d,"assigneeId")),403,"SCOPE_DENIED","仅差异负责人可申请重新分配");Map<String,Object> rp=c.get("repayments",repaymentId);c.check(Objects.equals(s(d,"reference"),s(rp,"reference")) && "EXCEPTION".equals(s(rp,"status")),409,"STATE_CONFLICT","不是该差异的迟到待分配款");evidence(c,evidenceIds,c.actorId(),true);
        return c.create("lateAllocationRequests",map("differenceId",differenceId,"repaymentId",repaymentId,"repaymentVersion",n(rp,"version"),"merchantUid",s(d,"merchantUid"),"makerId",c.actorId(),"checkerId",null,"status","REQUESTED","amountMinor",n(rp,"unallocatedMinor"),"evidenceIds",new ArrayList<>(evidenceIds),"expiresAt",c.now().plusSeconds(86400).toString()));
    }
    public Map<String,Object> reviewLateAllocation(SimContext c,String requestId,Map<String,Object> b){
        Map<String,Object> req=c.get("lateAllocationRequests",requestId);c.requireScope(s(req,"merchantUid"),null,null,"LEDGER_CHECKER");c.version(req,b);c.independent(s(req,"makerId"));validRequest(c,req);c.check("REQUESTED".equals(s(req,"status")),409,"STATE_CONFLICT","重新分配申请已处理");evidence(c,SimContext.<String>list(b,"evidenceIds"),s(req,"makerId"),true);required(c,b,"reason",5,500);
        Map<String,Object> rp=c.get("repayments",s(req,"repaymentId"));c.check(n(rp,"version")==n(req,"repaymentVersion"),409,"VERSION_CONFLICT","待分配款已变化");String decision=s(b,"decision");c.check(Arrays.asList("APPROVE","REJECT").contains(decision),422,"VALIDATION_FAILED","未知复核决定");
        req.put("checkerId",c.actorId());req.put("status","APPROVE".equals(decision)?"APPROVED":"REJECTED");req.put("decisionEvidenceIds",new ArrayList<>(list(b,"evidenceIds")));req.put("reason",s(b,"reason"));c.bump(req);
        if("APPROVE".equals(decision)){FinanceModule.applyHeldRepayment(c,rp,requestId);req.put("status","POSTED");c.bump(req);}c.audit("LATE_ALLOCATION_REVIEWED","lateAllocationRequests",requestId,map("decision",decision,"repaymentId",s(rp,"id")));return req;
    }
    private static void setSla(SimContext c,Map<String,Object> d){
        String severity=s(d,"severity");boolean critical="S0".equals(severity);d.put("ackDueAt",critical?c.now().plusSeconds(900).toString():workingHoursAfter(c.now(),"S1".equals(severity)?4:9).toString());d.put("dueAt",critical?c.now().plusSeconds(7200).toString():workingHoursAfter(c.now(),"S1".equals(severity)?9:27).toString());d.put("slaPolicy","CANDIDATE_WEEKDAYS_09_18_ASIA_SHANGHAI; public-holiday calendar and business signoff pending");
    }
    /** Explicit synthetic test calendar: weekdays09-18 Shanghai, no unapproved public-holiday assumptions. */
    public static Instant workingHoursAfter(Instant instant,int hours){
        ZonedDateTime current=instant.atZone(ZONE);long remaining=Math.multiplyExact((long)hours,3600L);
        while(remaining>0){
            DayOfWeek day=current.getDayOfWeek();if(day==DayOfWeek.SATURDAY || day==DayOfWeek.SUNDAY){current=current.toLocalDate().plusDays(1).atTime(9,0).atZone(ZONE);continue;}
            ZonedDateTime open=current.toLocalDate().atTime(9,0).atZone(ZONE),close=current.toLocalDate().atTime(18,0).atZone(ZONE);if(current.isBefore(open))current=open;if(!current.isBefore(close)){current=current.toLocalDate().plusDays(1).atTime(9,0).atZone(ZONE);continue;}
            long available=Duration.between(current,close).getSeconds(),part=Math.min(remaining,available);current=current.plusSeconds(part);remaining-=part;
        }
        return current.toInstant();
    }
    /** Durable worker advancement is explicit; uses synthetic elapsed-time SLA pending reviewed calendar. */
    public static void escalateOverdueDifferences(SimContext c){for(Map<String,Object> d:c.all("differences"))if(!"CLOSED".equals(s(d,"status")) && !c.now().isBefore(Instant.parse(s(d,"dueAt"))) && !bool(d,"escalated")){d.put("escalated",true);d.put("escalatedAt",c.now().toString());c.bump(d);c.notify(s(d,"assigneeId"),"模拟对账差异已超时，请复核","differences",s(d,"id"));c.audit("DIFFERENCE_OVERDUE","differences",s(d,"id"),map("severity",s(d,"severity")));}}
    private static String eventHash(SimContext c,Map<String,Object> p){return c.hashObject(map("providerEventId",s(p,"providerEventId"),"reference",s(p,"reference"),"eventType",s(p,"eventType"),"amountMinor",n(p,"amountMinor"),"merchantUid",s(p,"merchantUid"),"currency",s(p,"currency"),"occurredAt",s(p,"occurredAt"),"environment",s(p,"environment")));}
    public static String buildChannelBill(SimContext c,String merchant,String businessDate){c.requireScope(merchant,null,null,"LEDGER_MAKER","LEDGER_CHECKER","SIM_CONTROLLER");date(c,businessDate);StringBuilder out=new StringBuilder(HEADER+"\n");for(Map<String,Object> in:c.all("inbox")){Map<String,Object> p=payload(in);if(merchant.equals(s(p,"merchantUid")) && businessDate.equals(occurrenceDate(p)))out.append(csvLine(Arrays.asList("SIM-BILL-1","SIMULATION",businessDate,merchant,s(p,"providerEventId"),s(p,"reference"),s(p,"eventType"),Long.toString(n(p,"amountMinor")),"CNY",s(p,"occurredAt"),eventHash(c,p))));}return out.toString();}
    private static String csvLine(List<String> values){StringBuilder s=new StringBuilder();for(int i=0;i<values.size();i++){if(i>0)s.append(',');String value=values.get(i)==null?"":values.get(i);if(!value.isEmpty() && "=+-@".indexOf(value.charAt(0))>=0)value="'"+value;s.append('"').append(value.replace("\"","\"\"")).append('"');}return s.append('\n').toString();}
    private static List<Map<String,Object>> parseCsv(SimContext c,String content){
        if(content.startsWith("\ufeff"))content=content.substring(1);List<List<String>> records=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false,afterQuote=false;
        for(int i=0;i<content.length();i++){char ch=content.charAt(i);if(quoted){if(ch=='"'){if(i+1<content.length() && content.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;afterQuote=true;}}else field.append(ch);continue;}
            if(ch=='"'){c.check(field.length()==0 && !afterQuote,422,"VALIDATION_FAILED","CSV引号无效");quoted=true;}
            else if(ch==','){row.add(field.toString());field.setLength(0);afterQuote=false;}
            else if(ch=='\n' || ch=='\r'){if(ch=='\r' && i+1<content.length() && content.charAt(i+1)=='\n')i++;row.add(field.toString());records.add(row);row=new ArrayList<>();field.setLength(0);afterQuote=false;}
            else{c.check(!afterQuote,422,"VALIDATION_FAILED","CSV闭引号后存在内容");field.append(ch);}}
        c.check(!quoted,422,"VALIDATION_FAILED","CSV引号未关闭");if(field.length()>0 || !row.isEmpty() || afterQuote){row.add(field.toString());records.add(row);}c.check(!records.isEmpty() && records.size()<=100001,422,"VALIDATION_FAILED","CSV为空或行数超限");c.check(String.join(",",records.get(0)).equals(HEADER),422,"VALIDATION_FAILED","CSV表头与SIM-BILL-1不符");
        List<Map<String,Object>> out=new ArrayList<>();for(int i=1;i<records.size();i++){List<String> v=records.get(i);c.check(v.size()==11,422,"VALIDATION_FAILED","CSV第"+(i+1)+"行字段数错误");c.check("SIM-BILL-1".equals(v.get(0)) && "SIMULATION".equals(v.get(1)) && "CNY".equals(v.get(8)),422,"VALIDATION_FAILED","只接受SIM-BILL-1模拟人民币账单");date(c,v.get(2));c.check(v.get(3).matches("s[0-9]{7}") && !v.get(4).isEmpty() && v.get(4).length()<=100 && !v.get(5).isEmpty() && v.get(5).length()<=80 && Arrays.asList("PAYMENT_CONFIRMED","REFUND_CONFIRMED").contains(v.get(6)),422,"VALIDATION_FAILED","CSV业务字段无效");long amount;try{amount=Long.parseLong(v.get(7));c.check(amount>0 && amount<=100000000,422,"VALIDATION_FAILED","CSV金额范围错误");Instant.parse(v.get(9));}catch(NumberFormatException|java.time.format.DateTimeParseException e){throw new SimException(422,"VALIDATION_FAILED","CSV金额或时间格式无效");}
            Map<String,Object> p=map("providerEventId",v.get(4),"reference",v.get(5),"eventType",v.get(6),"amountMinor",amount,"merchantUid",v.get(3),"currency",v.get(8),"occurredAt",v.get(9),"environment",v.get(1));
            c.check(occurrenceDate(p).equals(v.get(2)),422,"VALIDATION_FAILED","CSV业务日与上海时区发生时间不一致");c.check(v.get(10).matches("[a-f0-9]{64}") && eventHash(c,p).equals(v.get(10)),422,"VALIDATION_FAILED","CSV事件摘要错误（当前候选规范固定JSON字段顺序）");p.put("businessDate",v.get(2));p.put("payloadSha256",v.get(10));out.add(p);}
        return out;
    }
}
