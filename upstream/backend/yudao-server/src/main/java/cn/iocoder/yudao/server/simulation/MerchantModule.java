package cn.iocoder.yudao.server.simulation;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Merchant review and scoped catalogue, separate from real merchant admission. */
public final class MerchantModule implements SimModule {
    private static final Set<String> OPS=new HashSet<>(Arrays.asList("createMerchantApplication","getMerchantApplication","reviewMerchantApplication","createStore","listStores","updateStore","createProduct","listProducts","updateProduct","changeMerchantState"));
    public boolean supports(String op){return OPS.contains(op);}
    public Object execute(String op,SimContext c,Map<String,Object> b,Map<String,String> p){
        switch(op){
            case "createMerchantApplication":return apply(c,b);
            case "getMerchantApplication":{
                Map<String,Object> r=c.get("applications",p.get("id"));readable(c,r);return appDto(c,r);}
            case "reviewMerchantApplication":return review(c,b,p);
            case "createStore":return createStore(c,b);
            case "listStores":{
                c.requireRoles("USER","OWNER","CLERK");List<Map<String,Object>> result=new ArrayList<>();
                for(Map<String,Object> r:c.all("stores"))if(visibleStore(c,r))result.add(storeDto(c,r));return c.page(result,p);}
            case "updateStore":{
                Map<String,Object> r=c.get("stores",p.get("id"));c.requireScope(s(r,"merchantUid"),s(r,"id"),null,"OWNER");c.version(r,b);
                if(b.containsKey("name"))r.put("name",b.get("name"));if(b.containsKey("active"))r.put("active",b.get("active"));c.bump(r);return storeDto(c,r);}
            case "createProduct":{
                Map<String,Object> store=c.get("stores",s(b,"storeId"));c.requireScope(s(store,"merchantUid"),s(store,"id"),null,"OWNER");active(c,store);
                c.check(n(b,"priceMinor")>0,422,"VALIDATION_FAILED","套餐金额必须为正整数分");Map<String,Object> r=c.create("products",map("storeId",s(store,"id"),"merchantUid",s(store,"merchantUid"),"name",s(b,"name"),"priceMinor",n(b,"priceMinor"),"active",true));return productDto(c,r);}
            case "listProducts":{
                c.requireRoles("USER","OWNER","CLERK");List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> r:c.all("products")){
                    Map<String,Object> store=c.get("stores",s(r,"storeId"));if(visibleStore(c,store) && (bool(r,"active") || c.inScope(s(store,"merchantUid"),s(store,"id"),null,"OWNER","CLERK")) && (p.get("storeId")==null || p.get("storeId").equals(s(r,"storeId"))))result.add(productDto(c,r));}
                return c.page(result,p);}
            case "updateProduct":{
                Map<String,Object> r=c.get("products",p.get("id"));c.requireScope(s(r,"merchantUid"),s(r,"storeId"),null,"OWNER");c.version(r,b);
                for(String key:Arrays.asList("name","priceMinor","active"))if(b.containsKey(key))r.put(key,b.get(key));c.bump(r);return productDto(c,r);}
            case "changeMerchantState":return state(c,b,p);
            default:throw new IllegalArgumentException(op);
        }
    }
    private Object apply(SimContext c,Map<String,Object> b){
        c.requireRoles("USER");c.check(s(b,"merchantName")!=null && !s(b,"merchantName").trim().isEmpty(),422,"VALIDATION_FAILED","模拟商家名称不可为空");
        noOpenApplication(c);
        String mid=c.id();Map<String,Object> merchant=c.create("merchants",map("id",mid,"uid",String.format("s%07d",Long.parseLong(mid)),"ownerId",c.actorId(),"name",s(b,"merchantName"),"regionCode",s(b,"regionCode"),"status","DRAFT"));
        Map<String,Object> app=c.create("applications",map("merchantId",mid,"applicantId",c.actorId(),"status","SUBMITTED","materialVersion",1L,"contractVersion",s(b,"contractVersion"),"materialSummary",s(b,"materialSummary"),"materialHash",c.hashObject(b),"reviewReason",""));
        app.put("draftMaterials",new LinkedHashMap<>(b));app.put("submittedMaterialVersion",1L);snapshot(c,app,b);history(c,app,"SUBMITTED",null);
        return appDto(c,app);
    }
    private Object review(SimContext c,Map<String,Object> b,Map<String,String> p){
        c.requireRoles("MERCHANT_REVIEWER");Map<String,Object> r=c.get("applications",p.get("id"));reviewer(c,r);c.version(r,b);c.independent(s(r,"applicantId"));
        c.check("SUBMITTED".equals(s(r,"status")),409,"STATE_CONFLICT","此申请当前不可复核");c.check(s(b,"reason")!=null && !s(b,"reason").trim().isEmpty(),422,"VALIDATION_FAILED","必须记录复核理由");
        boolean approve="APPROVE".equals(s(b,"decision"));r.put("status",approve?"APPROVED":"REJECTED");r.put("checkerId",c.actorId());r.put("reviewReason",s(b,"reason"));r.put("evidenceIds",list(b,"evidenceIds"));c.bump(r);
        Map<String,Object> m=c.get("merchants",s(r,"merchantId"));m.put("status",approve?"APPROVED":"DRAFT");c.bump(m);
        if(approve){c.create("grants",map("userId",s(m,"ownerId"),"role","OWNER","merchantUid",s(m,"uid"),"status","ACTIVE","validFrom",c.now().toString(),"approvalId",s(r,"id")));c.state.meta.put("grantVersion",n(c.state.meta,"grantVersion")+1);}
        history(c,r,approve?"APPROVED":"REJECTED",s(b,"reason"));
        c.notify(s(r,"applicantId"),approve?"模拟商家申请已通过":"模拟商家申请未通过","merchantApplication",s(r,"id"),approve?"MERCHANT_APPROVED":"MERCHANT_REJECTED",n(r,"materialVersion"));return appDto(c,r);
    }
    /** Supplemental lifecycle keeps submitted materials immutable and draft edits private to the applicant. */
    public Object supplemental(String op,SimContext c,Map<String,Object> b,Map<String,String> p){
        if("saveMerchantApplicationDraft".equals(op)){
            c.requireRoles("USER");noOpenApplication(c);String mid=c.id();
            Map<String,Object> materials=new LinkedHashMap<>(b);
            c.create("merchants",map("id",mid,"uid",String.format("s%07d",Long.parseLong(mid)),"ownerId",c.actorId(),"name",s(b,"merchantName"),"regionCode",s(b,"regionCode"),"status","DRAFT"));
            Map<String,Object> r=c.create("applications",map("merchantId",mid,"applicantId",c.actorId(),"status","DRAFT","materialVersion",1L,"reviewReason","","draftMaterials",materials,"submittedMaterialVersion",0L));
            history(c,r,"DRAFT_CREATED",null);return detail(c,r);
        }
        Map<String,Object> r=c.get("applications",p.get("id"));
        if("requestMerchantApplicationInformation".equals(op)){
            reviewer(c,r);c.version(r,b);c.independent(s(r,"applicantId"));
            c.check("SUBMITTED".equals(s(r,"status")),409,"STATE_CONFLICT","仅待复核申请可要求补充资料");
            r.put("status","NEEDS_INFO");r.put("reviewReason",s(b,"reason"));r.put("checkerId",c.actorId());c.bump(r);history(c,r,"NEEDS_INFO",s(b,"reason"));
            c.notify(s(r,"applicantId"),"模拟商家申请需要补充资料","merchantApplication",s(r,"id"),"MERCHANT_NEEDS_INFO",n(r,"materialVersion"));
            return detail(c,r);
        }
        if("merchantApplicationHistory".equals(op)){readable(c,r);return detail(c,r);}
        c.check(c.actorId().equals(s(r,"applicantId")),403,"SCOPE_DENIED","只能操作本人申请草稿");
        if("getMerchantApplicationDraft".equals(op))return detail(c,r);
        c.version(r,b);c.check(Arrays.asList("DRAFT","NEEDS_INFO").contains(s(r,"status")),409,"STATE_CONFLICT","审核中或终态申请不可改写资料");
        if("editMerchantApplicationDraft".equals(op)){
            c.check(b.size()>1,422,"VALIDATION_FAILED","至少修改一个资料字段");
            Map<String,Object> materials=materials(r);for(String field:MATERIAL_FIELDS)if(b.containsKey(field))materials.put(field,b.get(field));
            r.put("draftMaterials",materials);c.bump(r);history(c,r,"DRAFT_UPDATED",null);return detail(c,r);
        }
        if("submitMerchantApplicationDraft".equals(op)){
            Map<String,Object> materials=materials(r);for(String field:MATERIAL_FIELDS)c.check(s(materials,field)!=null&&!s(materials,field).trim().isEmpty(),422,"VALIDATION_FAILED","提交前请补全"+field);
            long prior=n(r,"submittedMaterialVersion");if(prior==0&&r.get("submittedMaterialVersion")==null)prior=n(r,"materialVersion");
            c.check(prior==0||!Objects.equals(s(r,"materialHash"),c.hashObject(materials)),409,"STATE_CONFLICT","补件后需修改资料才能重新提交");
            r.put("materialVersion",prior+1);r.put("submittedMaterialVersion",prior+1);r.put("status","SUBMITTED");r.put("reviewReason","");r.remove("checkerId");r.remove("evidenceIds");
            for(String field:MATERIAL_FIELDS)r.put(field,materials.get(field));r.put("materialHash",c.hashObject(materials));c.bump(r);
            Map<String,Object> m=c.get("merchants",s(r,"merchantId"));m.put("name",materials.get("merchantName"));m.put("regionCode",materials.get("regionCode"));c.bump(m);
            snapshot(c,r,materials);history(c,r,prior==0?"SUBMITTED":"RESUBMITTED",null);return detail(c,r);
        }
        throw new IllegalArgumentException(op);
    }
    private static final List<String> MATERIAL_FIELDS=Arrays.asList("merchantName","regionCode","contractVersion","materialSummary");
    private static Map<String,Object> materials(Map<String,Object> r){
        Map<String,Object> result=new LinkedHashMap<>();Object draft=r.get("draftMaterials");
        if(draft instanceof Map)result.putAll((Map<String,Object>)draft);else for(String field:MATERIAL_FIELDS)if(r.containsKey(field))result.put(field,r.get(field));return result;
    }
    private static void noOpenApplication(SimContext c){
        for(Map<String,Object> a:c.all("applications"))c.check(!c.actorId().equals(s(a,"applicantId"))||!Arrays.asList("DRAFT","SUBMITTED","NEEDS_INFO").contains(s(a,"status")),409,"STATE_CONFLICT","已有草稿或待处理申请，请继续原申请");
    }
    private static void reviewer(SimContext c,Map<String,Object> r){Map<String,Object> m=c.get("merchants",s(r,"merchantId"));c.requireScope(s(m,"uid"),null,null,"MERCHANT_REVIEWER");}
    private static void readable(SimContext c,Map<String,Object> r){if(!c.actorId().equals(s(r,"applicantId"))){c.check(!"DRAFT".equals(s(r,"status")),403,"SCOPE_DENIED","未提交草稿仅申请人可见");reviewer(c,r);}}
    private static void snapshot(SimContext c,Map<String,Object> r,Map<String,Object> materials){
        c.create("applicationMaterials",map("applicationId",s(r,"id"),"applicantId",s(r,"applicantId"),"materialVersion",n(r,"materialVersion"),"materials",new LinkedHashMap<>(materials),"materialHash",c.hashObject(materials),"submittedBy",c.actorId(),"submittedAt",c.now().toString()));
    }
    private static void history(SimContext c,Map<String,Object> r,String action,String reason){
        c.create("applicationHistory",map("applicationId",s(r,"id"),"applicantId",s(r,"applicantId"),"action",action,"status",s(r,"status"),"applicationVersion",n(r,"version"),"materialVersion",n(r,"materialVersion"),"materialHash",r.get("materialHash"),"reason",reason,"actorId",c.actorId(),"requestId",c.state.meta.get("requestId"),"occurredAt",c.now().toString()));
    }
    private Map<String,Object> detail(SimContext c,Map<String,Object> r){
        Map<String,Object> dto=appDto(c,r);dto.put("applicantId",s(r,"applicantId"));dto.put("submittedMaterialVersion",n(r,"submittedMaterialVersion"));dto.put("merchantUid",s(c.get("merchants",s(r,"merchantId")),"uid"));Map<String,Object> visibleMaterials=materials(r);
        if(!c.actorId().equals(s(r,"applicantId"))){visibleMaterials=new LinkedHashMap<>();for(String field:MATERIAL_FIELDS)if(r.containsKey(field))visibleMaterials.put(field,r.get(field));for(Map<String,Object> item:c.all("applicationMaterials"))if(s(r,"id").equals(s(item,"applicationId"))&&n(r,"materialVersion")==n(item,"materialVersion"))visibleMaterials=new LinkedHashMap<>((Map<String,Object>)item.get("materials"));}
        dto.put("materials",visibleMaterials);dto.put("simulationOnly",true);
        List<Map<String,Object>> versions=new ArrayList<>(),events=new ArrayList<>();
        for(Map<String,Object> item:c.all("applicationMaterials"))if(s(r,"id").equals(s(item,"applicationId")))versions.add(new LinkedHashMap<>(item));
        for(Map<String,Object> item:c.all("applicationHistory"))if(s(r,"id").equals(s(item,"applicationId")))events.add(new LinkedHashMap<>(item));
        dto.put("materialHistory",versions);dto.put("history",events);return dto;
    }
    private Object createStore(SimContext c,Map<String,Object> b){
        Map<String,Object> m=IdentityModule.merchant(c,s(b,"merchantUid"));c.requireScope(s(m,"uid"),null,null,"OWNER");c.check("APPROVED".equals(s(m,"status")),409,"STATE_CONFLICT","模拟商家未通过或已停用");
        Map<String,Object> r=c.create("stores",map("merchantUid",s(m,"uid"),"name",s(b,"name"),"regionCode",s(b,"regionCode"),"addressText",s(b,"addressText"),"active",true));return storeDto(c,r);
    }
    private Object state(SimContext c,Map<String,Object> b,Map<String,String> p){
        c.requireRoles("MERCHANT_REVIEWER");Map<String,Object> m=c.get("merchants",p.get("id"));c.version(m,b);c.independent(s(m,"ownerId"));String desired=s(b,"status");
        c.check(!"DRAFT".equals(s(m,"status")) && !"EXITED".equals(s(m,"status")),409,"STATE_CONFLICT","商家当前状态不能执行此变更");
        if("EXITED".equals(desired)){
            for(Map<String,Object> r:c.all("receivables"))if(s(m,"uid").equals(s(r,"merchantUid")))c.check(n(r,"outstandingMinor")==0 && !bool(r,"hasOpenDispute"),409,"STATE_CONFLICT","尚有未结清模拟应收或异议");
            for(Map<String,Object> r:c.all("refunds"))if(s(m,"uid").equals(s(r,"merchantUid")))c.check(n(r,"returnPayableMinor")==0 || Arrays.asList("SUCCEEDED","REJECTED").contains(s(r,"status")),409,"STATE_CONFLICT","尚有退款义务未完成");
            for(Map<String,Object> r:c.all("reservations"))if(r.get("orderId")!=null && s(m,"uid").equals(s(c.get("orders",s(r,"orderId")),"merchantUid")))c.check(!"HELD".equals(s(r,"status")),409,"STATE_CONFLICT","尚有生效预占");
        }
        m.put("status",desired);m.put("stateReason",s(b,"reason"));c.bump(m);return map("accepted",true,"resourceId",s(m,"id"));
    }
    private boolean visibleStore(SimContext c,Map<String,Object> s){return c.inScope(s(s,"merchantUid"),s(s,"id"),null,"OWNER","CLERK") || (bool(s,"active") && "APPROVED".equals(s(IdentityModule.merchant(c,s(s,"merchantUid")),"status")));}
    public static void active(SimContext c,Map<String,Object> s){c.check(bool(s,"active") && "APPROVED".equals(s(IdentityModule.merchant(c,s(s,"merchantUid")),"status")),409,"STATE_CONFLICT","门店或商家已停止新增模拟交易");}
    private Map<String,Object> appDto(SimContext c,Map<String,Object> r){return c.project(r,"id","merchantId","status","materialVersion","reviewReason","version");}
    public static Map<String,Object> storeDto(SimContext c,Map<String,Object> r){return c.project(r,"id","merchantUid","name","regionCode","active","version");}
    public static Map<String,Object> productDto(SimContext c,Map<String,Object> r){return c.project(r,"id","storeId","name","priceMinor","active","version");}
}
