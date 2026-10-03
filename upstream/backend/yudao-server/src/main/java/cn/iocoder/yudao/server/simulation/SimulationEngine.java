package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Auth, schema, rate-limit, idempotency and transaction boundary shared by all APIs. */
public final class SimulationEngine {
    public final ContractCatalog catalog=new ContractCatalog();
    private final SimStore store;private final Clock clock;private final MockKeyRegistry keys;
    private final List<SimModule> modules=Arrays.asList(new IdentityModule(),new MerchantModule(),new CommerceModule(),new FinanceModule(),new ReconciliationModule());
    public SimulationEngine(SimStore store,Clock clock){this(store,clock,new MockKeyRegistry());}
    public SimulationEngine(SimStore store,Clock clock,MockKeyRegistry keys){this.store=store;this.clock=clock;this.keys=keys;}
    public Object request(String method,String path,Map<String,Object> body,Map<String,String> params,Map<String,String> headers,byte[] raw,String requestId){
        ContractCatalog.Operation operation=catalog.match(method,path,params);catalog.request(operation,body,params);
        // Expiry is committed independently; a rejected command cannot resurrect stale holds.
        sweep();
        countRequest(operation,headers.get("authorization"),params.get("_remote"));
        try{
            Outcome outcome=store.transaction(state->{
                state.meta.put("requestId",requestId);SimContext anon=new SimContext(state,null,clock.instant());String actor=IdentityModule.authenticate(anon,headers.get("authorization"));
                if(operation.callback){validateSignature(anon,headers,raw);actor="900";}
                SimContext c=new SimContext(state,actor,clock.instant());params.put("_actorId",actor);params.put("_accessHash",headers.get("authorization")==null?null:c.hash(headers.get("authorization")));
                if(!operation.anonymous && !operation.callback)c.requireRoles(operation.roles.toArray(new String[0]));
                Map<String,String> semanticParams=new TreeMap<>();for(Map.Entry<String,String> entry:params.entrySet())if(!entry.getKey().startsWith("_"))semanticParams.put(entry.getKey(),entry.getValue());
                String requestHash=c.hashObject(map("path",path,"parameters",semanticParams,"body",body)),idempotencyId=null;Map<String,Object> record=null;
                List<Map<String,Object>> grants=new ArrayList<>();for(Map<String,Object> grant:c.all("grants"))if(Objects.equals(actor,s(grant,"userId")) && "ACTIVE".equals(s(grant,"status")) && (grant.get("validFrom")==null || !c.now().isBefore(Instant.parse(s(grant,"validFrom")))) && (grant.get("validTo")==null || c.now().isBefore(Instant.parse(s(grant,"validTo")))))grants.add(grant);String scopeHash=c.hashObject(grants);
                if(operation.idempotency){
                    String key=headers.get("idempotency-key");c.check(key!=null && key.length()>=16 && key.length()<=128,422,"VALIDATION_FAILED","本操作需要16至128字符的Idempotency-Key");
                    idempotencyId=c.hash((actor==null?"ANON":actor)+"|"+operation.id+"|"+key);record=state.table("idempotency").get(idempotencyId);
                    if(record!=null){c.check(scopeHash.equals(s(record,"scopeHash")),403,"SCOPE_DENIED","授权范围已变化，不能重放旧结果");c.check(requestHash.equals(s(record,"requestHash")),409,"IDEMPOTENCY_CONFLICT","同一幂等键不能提交不同内容");return new Outcome(record.get("response"),null);}
                }
                Object data=null;boolean found=false;
                try{
                    for(SimModule module:modules)if(module.supports(operation.id)){data=module.execute(operation.id,c,body,params);found=true;break;}
                    c.check(found,503,"SERVICE_UNAVAILABLE","该模拟功能尚未实现");
                }catch(SimException e){if(e.commitSecurityState){securityAudit(c,operation.id,e.code);return new Outcome(null,e);}throw e;}
                Map<String,Object> response=map("data",data,"meta",map("requestId",requestId,"serverTime",c.now().toString(),"environment","SIMULATION"));
                if(operation.responseSchema!=null && catalog.spec.path("components").path("schemas").path(operation.responseSchema).path("properties").has("code"))response.put("code",0);
                if(operation.responseSchema!=null)try{catalog.validate(operation.responseSchema,response);}catch(SimException schema){throw new IllegalStateException("Response contract mismatch for "+operation.id+": "+schema.getMessage());}
                if(operation.idempotency)c.create("idempotency",map("id",idempotencyId,"operationId",operation.id,"actorId",actor,"requestHash",requestHash,"scopeHash",scopeHash,"response",response,"status","COMPLETED"));
                if(!"GET".equals(operation.method))c.audit(operation.id,"api",params.get("id"),map("outcome","SUCCESS"));
                return new Outcome(response,null);
            });
            if(outcome.error!=null)throw outcome.error;return outcome.value;
        }catch(SimException e){
            if(!e.commitSecurityState)store.transaction(state->{SimContext c=new SimContext(state,null,clock.instant());state.meta.put("requestId",requestId);securityAudit(c,operation.id,e.code);return null;});
            throw e;
        }
    }
    public void sweep(){store.transaction(state->{SimContext c=new SimContext(state,null,clock.instant());CommerceModule.expireReservations(c);FinanceModule.retryPendingCallbacks(c);FinanceModule.expireDisputes(c);new ReconciliationModule().escalateOverdueDifferences(c);for(Map<String,Object> a:c.all("accounts"))CommerceModule.refreshAccount(c,s(a,"userUid"));return null;});}
    public Object fixtures(){return store.transaction(state->{SimContext c=new SimContext(state,null,clock.instant());List<Map<String,Object>> users=new ArrayList<>();for(Map<String,Object> u:c.all("users"))if(u.get("fixturePhone")!=null){List<String> roles=new ArrayList<>();roles.add("USER");for(Map<String,Object> g:c.all("grants"))if(s(u,"id").equals(s(g,"userId")) && "ACTIVE".equals(s(g,"status")))roles.add(s(g,"role"));users.add(map("phone",s(u,"fixturePhone"),"label",s(u,"displayName"),"uid",s(u,"uid"),"roles",roles));}return map("environment","SIMULATION","warning",s(state.meta,"fixtureWarning"),"mockCode","246810","privacyVersion",s(state.meta,"privacyVersion"),"agreementVersion",s(state.meta,"agreementVersion"),"users",users);});}
    public Object workspace(String access){sweep();return store.transaction(state->{SimContext a=new SimContext(state,null,clock.instant());String uid=IdentityModule.authenticate(a,access);return SimulationWorkspace.read(new SimContext(state,uid,clock.instant()));});}
    public Object supplemental(String operation,String id,Map<String,Object> body,String access){return store.transaction(state->{SimContext a=new SimContext(state,null,clock.instant());String uid=IdentityModule.authenticate(a,access);SimContext c=new SimContext(state,uid,clock.instant());c.actorId();ReconciliationModule module=new ReconciliationModule();
        if("lateRequest".equals(operation))return module.requestLateAllocation(c,s(body,"differenceId"),s(body,"repaymentId"),SimContext.<String>list(body,"evidenceIds"));
        if("lateReview".equals(operation))return module.reviewLateAllocation(c,id,body);
        if("channelBill".equals(operation)){String merchant=s(body,"merchantUid");c.requireScope(merchant,null,null,"LEDGER_MAKER","LEDGER_CHECKER","SIM_CONTROLLER");LocalDate.parse(s(body,"businessDate"));return ReconciliationModule.buildChannelBill(c,merchant,s(body,"businessDate"));}
        throw new SimException(404,"RESOURCE_NOT_FOUND","Unknown simulation control");});}
    public byte[] download(String id,String token,String access){return store.transaction(state->{SimContext a=new SimContext(state,null,clock.instant());String uid=IdentityModule.authenticate(a,access);SimContext c=new SimContext(state,uid,clock.instant());c.actorId();return new ReconciliationModule().downloadExport(c,id,token);});}
    public Object failure(Map<String,Object> b,String access,String requestId){return store.transaction(state->{SimContext a=new SimContext(state,null,clock.instant());String uid=IdentityModule.authenticate(a,access);SimContext c=new SimContext(state,uid,clock.instant());state.meta.put("requestId",requestId);c.check(bool(state.meta,"fixtureMode"),403,"SCOPE_DENIED","模拟夹具控制台不可用");return FinanceModule.simulateFailure(c,b);});}
    public Object providerResult(Map<String,Object> b,String access,boolean deliver){catalog.validate("MockResult",b);return store.transaction(state->{SimContext a=new SimContext(state,null,clock.instant());String uid=IdentityModule.authenticate(a,access);SimContext c=new SimContext(state,uid,clock.instant());c.check(bool(state.meta,"fixtureMode"),403,"SCOPE_DENIED","模拟夹具控制台不可用");return FinanceModule.simulateProviderResult(c,b,deliver);});}
    private void validateSignature(SimContext c,Map<String,String> h,byte[] raw){
        String timestamp=h.get("x-mock-timestamp"),nonce=h.get("x-mock-nonce"),signature=h.get("x-mock-signature"),key=h.get("x-mock-key-id");
        byte[] secret=keys.resolve(key,c.now());
        c.check(timestamp!=null && timestamp.matches("[0-9]{10,13}") && nonce!=null && nonce.matches("[A-Za-z0-9_-]{16,128}"),401,"AUTH_REQUIRED","模拟回调签名头无效");
        try{
            c.check(Math.abs(c.now().getEpochSecond()-Long.parseLong(timestamp))<=300,401,"AUTH_REQUIRED","模拟回调时间戳过期");
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            mac.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.US_ASCII));mac.update(raw);mac.update((byte)'\n');
            c.check(signature!=null && MessageDigest.isEqual(mac.doFinal(),Base64.getDecoder().decode(signature)),401,"AUTH_REQUIRED","模拟回调签名无效");
        }catch(SimException e){throw e;}catch(Exception e){throw new SimException(401,"AUTH_REQUIRED","模拟回调签名无效");}
        c.check(!c.state.table("signatureNonces").containsKey(nonce),409,"CALLBACK_CONFLICT","模拟回调nonce重复");
        c.create("signatureNonces",map("id",nonce,"requestHash",c.hash(new String(raw,StandardCharsets.UTF_8)),"expiresAt",c.now().plusSeconds(86400).toString()));
    }
    private void countRequest(ContractCatalog.Operation op,String access,String remote){
        store.transaction(state->{SimContext anon=new SimContext(state,null,clock.instant());String actor=null;try{actor=IdentityModule.authenticate(anon,access);}catch(SimException ignored){}
            String identity=actor==null?"anon:"+remote:actor;String profile="GET".equals(op.method)?"read":"mutation";
            String key=anon.hash(identity+"|"+profile+"|"+(anon.now().getEpochSecond()/60));Map<String,Object> r=state.table("rateLimits").get(key);
            if(r==null)r=anon.create("rateLimits",map("id",key,"count",0L,"expiresAt",anon.now().plusSeconds(120).toString()));
            anon.check(n(r,"count")<("read".equals(profile)?60:30),429,"RATE_LIMITED","请求过于频繁，请稍后重试");r.put("count",n(r,"count")+1);return null;
        });
    }
    private static void securityAudit(SimContext c,String operation,String code){c.create("audit",map("actorId",null,"actorUid","ANON","requestId",c.state.meta.get("requestId"),"action",operation,"resourceType","api","resourceId",null,"detail",map("code",code),"outcome","DENIED","occurredAt",c.now().toString()));}
    private static final class Outcome {final Object value;final SimException error;Outcome(Object value,SimException error){this.value=value;this.error=error;}}
}
