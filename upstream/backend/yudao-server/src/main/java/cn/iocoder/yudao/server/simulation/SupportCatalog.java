package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Shop additions have a separate strict contract; the original 76 operations stay frozen. */
public final class SupportCatalog {
    private final ContractCatalog contract;
    private final Map<String,ContractCatalog.Operation> operations=new LinkedHashMap<>();
    private final Map<String,JsonNode> schemas=new LinkedHashMap<>();
    private final Map<String,Set<String>> queries=new LinkedHashMap<>();
    public SupportCatalog(ContractCatalog contract){
        this.contract=contract;
        add("support.context","GET","/context",map());
        add("profile.get","GET","/profile",map());
        add("profile.update","POST","/profile",map("version",version(),"nickname",text(80,false),"avatarUrl",text(120,false),"email",text(254,true),"contactPhone",text(32,true)),"version","nickname","avatarUrl","email","contactPhone");
        add("support.overview","GET","/overview",map(),new String[0],"status","priority","includeArchived","page","limit","q","unitId");
        add("support.session","GET","/session",map(),new String[0],"id","beforeId");
        add("support.ticket","GET","/ticket",map(),new String[0],"id");
        add("support.open","POST","/open-session",map("unitId",id()),"unitId");
        add("support.send","POST","/send-message",map("sessionId",id(),"text",text(1000,true),"attachmentIds",attachments()),"sessionId");
        add("support.read","POST","/mark-read",map("sessionId",id(),"lastSeenMessageId",id()),"sessionId","lastSeenMessageId");
        add("support.createTicket","POST","/create-ticket",map("unitId",id(),"title",text(200,false),"content",text(1500,false),"priority",enumeration("LOW","MEDIUM","HIGH","NORMAL","IMPORTANT"),"orderId",id(),"attachmentIds",attachments()),"unitId","title","content","priority");
        add("support.reply","POST","/reply-ticket",map("ticketId",id(),"text",text(1000,false),"version",version(),"attachmentIds",attachments()),"ticketId","text","version");
        add("support.status","POST","/ticket-status",map("ticketId",id(),"status",enumeration("OPEN","IN_PROGRESS","RESOLVED","CLOSED"),"version",version()),"ticketId","status","version");
        for(String action:Arrays.asList("archive","restore"))add("support."+action,"POST","/"+action+"-ticket",map("ticketId",id(),"version",version(),"reason",text(500,false)),"ticketId","version","reason");
        add("resources.list","GET","/resources",map(),new String[0],"kind","q","audience");
        add("resources.detail","GET","/resource",map(),new String[0],"id");
        add("resources.adminList","GET","/admin/resources",map(),new String[0],"kind","q","audience","status");
        Map<String,Object> resource=map("kind",enumeration("KNOWLEDGE","MANUAL","UPDATE_LOG"),"title",text(160,false),"content",text(16000,false),"audience",enumeration("CUSTOMER","MERCHANT","ALL"));
        add("resources.create","POST","/resources/create",resource,"kind","title","content","audience");
        Map<String,Object> update=new LinkedHashMap<>(resource);update.put("version",version());
        add("resources.update","POST","/resources/update",update,new String[]{"kind","title","content","audience","version"},"id");
        add("resources.archive","POST","/resources/archive",map("version",version()),new String[]{"version"},"id");
        add("resources.history","GET","/resources/history",map(),new String[0],"id");
        add("manuals.list","GET","/manuals",map(),new String[0],"q","audience");
        add("manuals.detail","GET","/manual",map(),new String[0],"id");
        add("manuals.asset","GET","/manual-asset",map(),new String[0],"id","format","page");
        add("updates.list","GET","/updates",map(),new String[0],"q","audience");
        add("platform.ai.get","GET","/platform/ai",map());
        add("platform.ai.update","POST","/platform/ai",map("version",version(),"enabled",map("type","boolean"),"model",text(80,false),"welcomeMsg",text(500,false)),"version","enabled","model","welcomeMsg");
        add("platform.payments.list","GET","/platform/payments",map());
        add("platform.payments.update","POST","/platform/payments",map("version",version(),"displayEnabled",map("type","boolean"),"label",text(80,false)),new String[]{"version","displayEnabled","label"},"id");
        add("attachments.upload","POST","/attachments/upload",map("name",text(128,false),"mime",text(40,false),"base64",text(699052,false)),"name","mime","base64");
        add("attachments.detail","GET","/attachments/detail",map(),new String[0],"id");
        add("attachments.download","GET","/attachments/download",map(),new String[0],"id");
    }
    private static Map<String,Object> id(){return map("type","string","pattern","^[1-9][0-9]{0,19}$");}
    private static Map<String,Object> version(){return map("type","integer","minimum",1,"maximum",100000000);}
    private static Map<String,Object> text(int max,boolean empty){return map("type","string","minLength",empty?0:1,"maxLength",max);}
    private static Map<String,Object> enumeration(String... values){return map("type","string","enum",Arrays.asList(values));}
    private static Map<String,Object> attachments(){return map("type","array","maxItems",5,"uniqueItems",true,"items",id());}
    private void add(String op,String method,String path,Map<String,Object> fields,String... required){add(op,method,path,fields,required,new String[0]);}
    private void add(String id,String method,String path,Map<String,Object> fields,String[] required,String... query){
        ContractCatalog.Operation op=new ContractCatalog.Operation();op.id=id;op.method=method;op.path="/api/v1/support"+path;op.roles.add("USER");op.idempotency=!"GET".equals(method);op.definition=contract.json.valueToTree(map());
        operations.put(method+" "+op.path,op);schemas.put(id,contract.json.valueToTree(map("type","object","additionalProperties",false,"properties",fields,"required",Arrays.asList(required))));queries.put(id,new HashSet<>(Arrays.asList(query)));
    }
    public ContractCatalog.Operation match(String method,String path){return operations.get(method+" "+path);}
    public void validate(ContractCatalog.Operation op,Map<String,Object> body,Map<String,String> params){
        contract.validateSchema(schemas.get(op.id),body,op.id);
        for(Map.Entry<String,String> entry:params.entrySet())if(!entry.getKey().startsWith("_")){
            if(!queries.get(op.id).contains(entry.getKey()))throw new SimException(422,"VALIDATION_FAILED","未允许的查询参数"+entry.getKey());
            if(entry.getValue()==null||entry.getValue().length()>200)throw new SimException(422,"VALIDATION_FAILED","查询参数过长");
        }
        if(queries.get(op.id).contains("id")){
            String value=params.get("id");boolean payment="platform.payments.update".equals(op.id);
            if(value==null || !(payment?Arrays.asList("MOCK","ALIPAY","WECHAT","BANKCARD").contains(value):value.matches("[1-9][0-9]{0,19}")))throw new SimException(422,"VALIDATION_FAILED","缺少有效资源编号");
        }
    }
    @SuppressWarnings("unchecked") public void authorizeReplay(String op,SimContext c,Map<String,Object> body,Object response){
        schemaVersion(c,false);
        String type=body.containsKey("sessionId")?"session":body.containsKey("ticketId")?"ticket":null;
        if(type!=null)c.check(SupportModule.canReadResource(c,type,s(body,type+"Id")),404,"RESOURCE_NOT_FOUND","资源不存在或不可访问");
        if("attachments.upload".equals(op)){
            Map<String,Object> envelope=(Map<String,Object>)response,data=(Map<String,Object>)envelope.get("data");
            SupportAttachments.execute("attachments.detail",c,map(),Collections.singletonMap("id",s(data,"id")));
        }
    }
    public boolean supports(String op){return schemas.containsKey(op);}
    private boolean isMutation(String op){for(ContractCatalog.Operation value:operations.values())if(value.id.equals(op))return value.idempotency;return false;}
    private static void schemaVersion(SimContext c,boolean writing){
        Object version=c.state.meta.get("supportSchemaVersion");c.check(version==null||(version instanceof Integer||version instanceof Long)&&((Number)version).longValue()==1,503,"SUPPORT_SCHEMA_UNSUPPORTED","客服数据版本与当前服务不兼容");
        if(writing&&version==null)c.state.meta.put("supportSchemaVersion",1);
    }
    public Object execute(String op,SimContext c,Map<String,Object> body,Map<String,String> params){
        schemaVersion(c,isMutation(op));
        if(op.startsWith("profile."))return SupportProfileModule.execute(op,c,body);
        if(op.startsWith("support."))return SupportModule.execute(op,c,body,params);
        if(op.startsWith("attachments."))return SupportAttachments.execute(op,c,body,params);
        return SupportResources.execute(op,c,body,params);
    }
}
