package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Separately versioned simulation routes. Never changes the frozen 76-operation contract. */
public final class SupplementalCatalog {
    private final ContractCatalog contract;
    private final Map<String,JsonNode> schemas=new LinkedHashMap<>();
    private final List<ContractCatalog.Operation> operations=new ArrayList<>();
    public SupplementalCatalog(ContractCatalog contract){
        this.contract=contract;
        Map<String,Object> materials=map("merchantName",string(128),"regionCode",string(16),"contractVersion",string(40),"materialSummary",string(1000));
        add("saveMerchantApplicationDraft","POST","/merchant-application-drafts","USER",object(materials));
        Map<String,Object> patch=new LinkedHashMap<>(materials);patch.put("version",version());
        add("editMerchantApplicationDraft","PATCH","/merchant-application-drafts/{id}","USER",object(patch,"version"));
        add("getMerchantApplicationDraft","GET","/merchant-application-drafts/{id}","USER",object(map()));
        add("submitMerchantApplicationDraft","POST","/merchant-application-drafts/{id}/submit","USER",object(map("version",version()),"version"));
        add("merchantApplicationHistory","GET","/merchant-applications/{id}/history","USER",object(map()));
        add("requestMerchantApplicationInformation","POST","/admin/merchant-applications/{id}/request-information","MERCHANT_REVIEWER",object(map("version",version(),"reason",string(500)),"version","reason"));
        add("getNotificationFailureConfiguration","GET","/simulation-controls/notification-failures","SIM_CONTROLLER",object(map()));
        add("listNotificationRetryAudits","GET","/notification-outbox/{id}/retries","USER",object(map()));
        add("configureNotificationFailures","POST","/simulation-controls/notification-failures","SIM_CONTROLLER",object(map("version",version(),"remainingFailures",map("type","integer","minimum",0,"maximum",100),"userId",map("type","string","pattern","^[0-9]{1,20}$"),"reason",string(500)),"version","remainingFailures","reason"));
        add("retryNotificationDelivery","POST","/notifications/{id}/retry","SIM_CONTROLLER",object(map("version",version(),"reason",string(500)),"version","reason"));
        add("listNotificationOutbox","GET","/notification-outbox","USER",object(map()));
        add("listNotificationAttempts","GET","/notification-outbox/{id}/attempts","USER",object(map()));
    }
    private static Map<String,Object> string(int max){return map("type","string","minLength",1,"maxLength",max,"pattern","\\S");}
    private static Map<String,Object> version(){return map("type","integer","minimum",1,"maximum",100000000);}
    private static Map<String,Object> object(Map<String,Object> fields,String... required){return map("type","object","additionalProperties",false,"properties",fields,"required",Arrays.asList(required));}
    private void add(String id,String method,String path,String role,Map<String,Object> schema){
        ContractCatalog.Operation op=new ContractCatalog.Operation();op.id=id;op.method=method;op.path="/api/v1"+path;op.roles.add(role);op.idempotency=!"GET".equals(method);op.definition=contract.json.valueToTree(map());
        Matcher matcher=Pattern.compile("\\{([^}]+)\\}").matcher(op.path);StringBuffer regex=new StringBuffer();while(matcher.find()){op.pathNames.add(matcher.group(1));matcher.appendReplacement(regex,"([0-9]{1,20})");}matcher.appendTail(regex);op.regex=Pattern.compile("^"+regex+"$");operations.add(op);schemas.put(id,contract.json.valueToTree(schema));
    }
    public ContractCatalog.Operation match(String method,String path,Map<String,String> params){
        for(ContractCatalog.Operation op:operations)if(op.method.equals(method)){Matcher m=op.regex.matcher(path);if(m.matches()){for(int i=0;i<op.pathNames.size();i++)params.put(op.pathNames.get(i),m.group(i+1));return op;}}
        return null;
    }
    public void validate(ContractCatalog.Operation op,Map<String,Object> body,Map<String,String> params){
        contract.validateSchema(schemas.get(op.id),body,op.id);
        for(String name:params.keySet())if(!name.startsWith("_")&&!op.pathNames.contains(name))throw new SimException(422,"VALIDATION_FAILED","未允许的查询参数"+name);
    }
    public boolean supports(String op){return schemas.containsKey(op);}
    public Object execute(String op,SimContext c,Map<String,Object> b,Map<String,String> p){
        switch(op){
            case "getNotificationFailureConfiguration":return NotificationModule.failureConfiguration(c);
            case "listNotificationRetryAudits":return NotificationModule.visibleRetryAudits(c,p.get("id"));
            case "configureNotificationFailures":return NotificationModule.configureFailure(c,b);
            case "retryNotificationDelivery":return NotificationModule.retry(c,p.get("id"),b);
            case "listNotificationOutbox":return NotificationModule.visibleOutbox(c);
            case "listNotificationAttempts":return NotificationModule.visibleAttempts(c,p.get("id"));
            default:return new MerchantModule().supplemental(op,c,b,p);
        }
    }
}
