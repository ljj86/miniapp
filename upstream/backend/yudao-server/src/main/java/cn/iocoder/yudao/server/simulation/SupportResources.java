package cn.iocoder.yudao.server.simulation;

import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/**
 * Truthful local help and non-secret integration metadata. No network client, credentials,
 * provider testing or live payment operations exist here. Custom materials are stored in
 * the guarded simulation aggregate, with immutable resource/configuration history.
 */
public final class SupportResources {
    private static final String RESOURCES = "supportResources";
    private static final String CONFIG = "supportPlatformConfig";
    private static final String SEED_DATE = "2026-10-05T00:00:00Z";
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList("KNOWLEDGE", "MANUAL", "UPDATE_LOG"));
    private static final Set<String> AUDIENCES = new HashSet<>(Arrays.asList("CUSTOMER", "MERCHANT", "ALL"));
    private static final Set<String> PAYMENTS = new HashSet<>(Arrays.asList("MOCK", "ALIPAY", "WECHAT", "BANKCARD"));
    private SupportResources() {}

    public static Object execute(String op, SimContext c, Map<String,Object> b, Map<String,String> p) {
        c.requireRoles("USER");
        switch (op) {
            case "resources.list": return list(c, p, null, false);
            case "resources.detail": return detail(c, p.get("id"), null);
            case "manuals.list": return list(c, p, "MANUAL", false);
            case "manuals.detail": return detail(c, p.get("id"), "MANUAL");
            case "manuals.asset": return SupportManualAssets.execute(op,c,b,p);
            case "updates.list": return list(c, p, "UPDATE_LOG", false);
            case "resources.adminList": platform(c); return list(c, p, null, true);
            case "resources.create": platform(c); return create(c, b);
            case "resources.update": platform(c); return update(c, p.get("id"), b);
            case "resources.archive": platform(c); return archive(c, p.get("id"), b);
            case "resources.history": platform(c); return history(c, p.get("id"));
            case "platform.ai.get": platform(c); return ai(c);
            case "platform.ai.update": platform(c); return updateAi(c, b);
            case "platform.payments.list": platform(c); return Arrays.asList(payment(c, "MOCK"), payment(c, "ALIPAY"), payment(c, "WECHAT"), payment(c, "BANKCARD"));
            case "platform.payments.update": platform(c); return updatePayment(c, p.get("id"), b);
            default: throw new SimException(404, "RESOURCE_NOT_FOUND", "资料操作不存在");
        }
    }

    private static void platform(SimContext c) { c.requireScope(null, null, null, "SUPPORT"); }
    private static List<Map<String,Object>> list(SimContext c, Map<String,String> p, String forcedKind, boolean admin) {
        String kind = forcedKind == null ? p.get("kind") : forcedKind;
        String audience = p.get("audience"), status = p.get("status"), query = p.get("q");
        c.check(kind == null || KINDS.contains(kind), 422, "VALIDATION_FAILED", "资料类型无效");
        c.check(audience == null || AUDIENCES.contains(audience), 422, "VALIDATION_FAILED", "资料受众无效");
        c.check(status == null || (admin && ("ACTIVE".equals(status) || "ARCHIVED".equals(status))), 422, "VALIDATION_FAILED", "资料状态无效");
        c.check(query == null || (!query.trim().isEmpty() && query.length() <= 100), 422, "VALIDATION_FAILED", "搜索词应为1至100字符");
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> row : combined(c)) {
            if (!admin && !"ACTIVE".equals(s(row, "status"))) continue;
            if (kind != null && !kind.equals(s(row, "kind"))) continue;
            if (audience != null && !audience.equals(s(row, "audience")) && !"ALL".equals(s(row, "audience"))) continue;
            if (status != null && !status.equals(s(row, "status"))) continue;
            if (query != null && !(s(row, "title") + "\n" + s(row, "content")).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
            out.add(dto(c, row));
        }
        out.sort((a,b) -> { int cmp=s(b,"updatedAt").compareTo(s(a,"updatedAt")); return cmp == 0 ? Long.compare(n(b,"id"),n(a,"id")) : cmp; });
        return out;
    }
    private static Map<String,Object> detail(SimContext c, String id, String kind) {
        Map<String,Object> row = get(c, id);
        c.check(("ACTIVE".equals(s(row,"status")) || c.inScope(null,null,null,"SUPPORT")) &&
                (kind == null || kind.equals(s(row,"kind"))), 404, "RESOURCE_NOT_FOUND", "资料不存在或不可访问");
        return dto(c, row);
    }
    private static Map<String,Object> create(SimContext c, Map<String,Object> body) {
        keys(c, body, "kind", "title", "content", "audience");
        Map<String,Object> material = material(c, body);
        c.check(rows(c,RESOURCES).size() < 500, 409, "RESOURCE_QUOTA_EXCEEDED", "模拟资料最多保存500条（含归档）");
        material.putAll(map("status", "ACTIVE", "readOnly", false, "creatorId", c.actorId(), "environment", "SIMULATION"));
        Map<String,Object> row = c.create(RESOURCES, material);
        resourceHistory(c, row, "CREATED");
        return dto(c, row);
    }
    private static Map<String,Object> update(SimContext c, String id, Map<String,Object> body) {
        keys(c, body, "version", "kind", "title", "content", "audience");
        Map<String,Object> row = editable(c,id); version(c,row,body);
        Map<String,Object> material = material(c,body);
        c.check(!material.equals(c.project(row,"kind","title","content","audience")), 409, "MATERIAL_UNCHANGED", "资料内容未发生变化");
        row.putAll(material); c.bump(row); resourceHistory(c,row,"UPDATED");
        return dto(c,row);
    }
    private static Map<String,Object> archive(SimContext c, String id, Map<String,Object> body) {
        keys(c,body,"version"); Map<String,Object> row=editable(c,id); version(c,row,body);
        row.put("status","ARCHIVED"); row.put("archivedAt",c.now().toString()); c.bump(row); resourceHistory(c,row,"ARCHIVED");
        return dto(c,row);
    }
    private static Map<String,Object> editable(SimContext c, String id) {
        Map<String,Object> row=get(c,id);
        c.check(!bool(row,"readOnly"),403,"READ_ONLY_RESOURCE","内置资料仅供查阅，可另建资料补充");
        c.check("ACTIVE".equals(s(row,"status")),409,"STATE_CONFLICT","已归档资料不可修改");
        return row;
    }
    private static Map<String,Object> material(SimContext c, Map<String,Object> body) {
        String kind=string(c,body,"kind",20), title=string(c,body,"title",160), content=string(c,body,"content",16000), audience=string(c,body,"audience",20);
        c.check(KINDS.contains(kind) && AUDIENCES.contains(audience),422,"VALIDATION_FAILED","资料类型或受众无效");
        return map("kind",kind,"title",title,"content",content,"audience",audience);
    }
    private static List<Map<String,Object>> history(SimContext c,String id) {
        get(c,id); List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> event:rows(c,"resourceHistory")) if(id.equals(s(event,"resourceId"))) {
            Map<String,Object> copy=new LinkedHashMap<>(event);
            copy.put("snapshot",new LinkedHashMap<>((Map<String,Object>)event.get("snapshot"))); result.add(copy);
        }
        return result;
    }
    private static void resourceHistory(SimContext c,Map<String,Object> row,String action) {
        Map<String,Object> snapshot=dto(c,row);
        c.create("resourceHistory",map("resourceId",s(row,"id"),"resourceVersion",n(row,"version"),"action",action,
                "actorId",c.actorId(),"requestId",c.state.meta.get("requestId"),"snapshot",snapshot,"snapshotHash",c.hashObject(snapshot),"environment","SIMULATION"));
        c.audit("SUPPORT_RESOURCE_"+action,RESOURCES,s(row,"id"),map("resourceVersion",n(row,"version"),"snapshotHash",c.hashObject(snapshot)));
    }
    private static Map<String,Object> dto(SimContext c,Map<String,Object> row) {
        Map<String,Object> result=c.project(row,"id","kind","title","content","audience","status","version","createdAt","updatedAt","archivedAt","readOnly","environment","source");
        result.put("simulationOnly",true); result.put("contentFormat","PLAIN_TEXT");
        if("MANUAL".equals(s(row,"kind")) && bool(row,"readOnly")) result.put("availableAssets",SupportManualAssets.describe(s(row,"id")));
        return result;
    }
    private static Map<String,Object> get(SimContext c,String id) {
        c.check(id!=null && id.matches("[1-9][0-9]{0,19}"),422,"VALIDATION_FAILED","资料标识无效");
        Map<String,Object> row=lookup(c,RESOURCES,id);
        if(row!=null)return row;
        for(Map<String,Object> seed:seeds())if(id.equals(s(seed,"id")))return seed;
        throw new SimException(404,"RESOURCE_NOT_FOUND","资料不存在或不可访问");
    }
    private static List<Map<String,Object>> combined(SimContext c) { List<Map<String,Object>> result=seeds(); result.addAll(rows(c,RESOURCES)); return result; }

    private static Map<String,Object> ai(SimContext c) {
        Map<String,Object> row=lookup(c,CONFIG,"AI");
        Map<String,Object> result=row==null?map("id","AI","version",1L,"enabled",false,"model","unconfigured","welcomeMsg","您好，请描述您的问题。模拟客服不会调用外部AI服务。"):
                c.project(row,"id","version","enabled","model","welcomeMsg","updatedAt");
        result.putAll(map("status","NOT_CONNECTED","externalCallsEnabled",false,"secretConfigured",false,"configurationOnly",true,"environment","SIMULATION")); return result;
    }
    private static Map<String,Object> updateAi(SimContext c,Map<String,Object> body) {
        keys(c,body,"version","enabled","model","welcomeMsg"); version(c,ai(c),body);
        c.check(body.get("enabled") instanceof Boolean,422,"VALIDATION_FAILED","enabled必须为布尔值");
        String model=string(c,body,"model",80), welcome=string(c,body,"welcomeMsg",500);
        c.check(model.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}") && !model.toLowerCase(Locale.ROOT).startsWith("sk-"),422,"VALIDATION_FAILED","model应为模型名称，不能是地址或密钥");
        Map<String,Object> row=lookup(c,CONFIG,"AI");
        if(row==null)row=c.create(CONFIG,map("id","AI"));
        row.putAll(map("enabled",body.get("enabled"),"model",model,"welcomeMsg",welcome)); c.bump(row);
        Map<String,Object> result=ai(c); configHistory(c,"AI",result); return result;
    }
    private static Map<String,Object> payment(SimContext c,String id) {
        Map<String,Object> row=lookup(c,CONFIG,"PAYMENT_"+id);
        String label="MOCK".equals(id)?"模拟支付":"ALIPAY".equals(id)?"支付宝（未连接）":"WECHAT".equals(id)?"微信支付（未连接）":"银行卡（未连接）";
        Map<String,Object> result=row==null?map("id",id,"version",1L,"displayEnabled","MOCK".equals(id),"label",label):c.project(row,"version","displayEnabled","label","updatedAt");
        result.putAll(map("id",id,"status","MOCK".equals(id)?"READY":"NOT_CONNECTED","mode","MOCK".equals(id)?"MOCK":"NOT_CONNECTED",
                "ready","MOCK".equals(id),"connected",false,"chargesEnabled",false,"externalCallsEnabled",false,"secretConfigured",false,"configurationOnly",true,"environment","SIMULATION")); return result;
    }
    private static Map<String,Object> updatePayment(SimContext c,String id,Map<String,Object> body) {
        c.check(PAYMENTS.contains(id),422,"VALIDATION_FAILED","支付渠道无效");
        keys(c,body,"version","displayEnabled","label"); version(c,payment(c,id),body);
        c.check(body.get("displayEnabled") instanceof Boolean,422,"VALIDATION_FAILED","displayEnabled必须为布尔值");
        String label=string(c,body,"label",80); Map<String,Object> row=lookup(c,CONFIG,"PAYMENT_"+id);
        if(row==null)row=c.create(CONFIG,map("id","PAYMENT_"+id));
        row.putAll(map("displayEnabled",body.get("displayEnabled"),"label",label)); c.bump(row);
        Map<String,Object> result=payment(c,id); configHistory(c,"PAYMENT_"+id,result); return result;
    }
    private static void configHistory(SimContext c,String id,Map<String,Object> snapshot) {
        c.create("platformConfigHistory",map("configId",id,"configVersion",n(snapshot,"version"),"actorId",c.actorId(),"requestId",c.state.meta.get("requestId"),"snapshot",new LinkedHashMap<>(snapshot),"snapshotHash",c.hashObject(snapshot),"environment","SIMULATION"));
        c.audit("SUPPORT_PLATFORM_METADATA_UPDATED",CONFIG,id,map("configVersion",n(snapshot,"version"),"externalCallsEnabled",false));
    }
    private static Collection<Map<String,Object>> rows(SimContext c,String kind) { Map<String,Map<String,Object>> table=c.state.records.get(kind); return table==null?Collections.<Map<String,Object>>emptyList():table.values(); }
    private static Map<String,Object> lookup(SimContext c,String kind,String id) { Map<String,Map<String,Object>> table=c.state.records.get(kind); return table==null?null:table.get(id); }
    private static String string(SimContext c,Map<String,Object> body,String field,int max) {
        Object value=body.get(field); c.check(value instanceof String && !((String)value).trim().isEmpty() && ((String)value).length()<=max,422,"VALIDATION_FAILED",field+"无效或超过限制");
        String text=(String)value; for(int i=0;i<text.length();i++)c.check(!Character.isISOControl(text.charAt(i))||"\r\n\t".indexOf(text.charAt(i))>=0,422,"VALIDATION_FAILED",field+"包含控制字符"); return text;
    }
    private static void keys(SimContext c,Map<String,Object> b,String... keys) { c.check(new HashSet<>(Arrays.asList(keys)).containsAll(b.keySet()),422,"VALIDATION_FAILED","包含未允许的字段"); }
    private static void version(SimContext c,Map<String,Object> row,Map<String,Object> body) {
        Object value=body.get("version"); c.check(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long,422,"VALIDATION_FAILED","version必须为整数");
        c.check(n(body,"version")>=1 && n(body,"version")<=100000000,422,"VALIDATION_FAILED","version无效"); c.version(row,body);
    }

    /** Fresh deterministic maps per call: reading help never seeds or alters persisted state. */
    private static List<Map<String,Object>> seeds() {
        List<Map<String,Object>> rows=new ArrayList<>();
        rows.add(seed("1","MANUAL","顾客：模拟先吃后付操作手册","CUSTOMER",
                "本系统为隔离模拟环境，不提供真实授信、扣款或退款。\n1. 使用合成模拟账户登录，并阅读模拟条款后启用体验。\n2. 商家发布用餐请求后，顾客核对商品、金额与规则快照，再确认订单。\n3. 确认凭证与核销凭证用途不同；实际履约以商家核销为准。\n4. 履约生成应收；还款申请处于待处理时不会减少本金，只有确认的模拟结果才入账。\n5. 需要帮助时提交问题描述与最少必要的非敏感模拟附件。附件未经安全扫描，不要上传真实身份、支付或健康资料。"));
        rows.add(seed("2","MANUAL","商家：门店与履约操作手册","MERCHANT",
                "本手册描述Java模拟服务已有操作，不表示餐饮前端所有入口均已完成真实服务接线。\n1. 保存参与资料草稿，资料齐全后提交，由独立复核人审核。补件时保留旧提交记录。\n2. 在授权范围内维护门店、商品与价格；发布订单前核对商品版本。\n3. 发布后金额和规则快照固定。顾客确认后，使用核销凭证履约。\n4. 核销前取消只释放额度预占；核销后不能用取消删除应收本金。\n5. 商家可查看授权范围内的问题；平台全局客服处理能力仅由全局SUPPORT授权提供。"));
        rows.add(seed("3","MANUAL","平台：客服与配置边界手册","ALL",
                "全局SUPPORT授权可维护客服资料与非敏感接入元数据；带商户、门店或账簿范围的SUPPORT授权不能升格为全局平台权限。\n资料修改使用版本号并保留不可变历史，归档资料不再出现在普通资料列表。\nAI设置仅保存启用展示意向、模型名称和欢迎语，不接受密钥或API地址；状态始终为未连接，不发送外部请求。\n模拟支付为唯一READY渠道，不扣取真实资金；支付宝、微信与银行卡仍未连接。展示开关不会建立通道连接。\n附件存入已有模拟JSON聚合，经过类型与容量检查但未经病毒扫描。"));
        rows.add(seed("4","KNOWLEDGE","为什么提交还款后账单仍未减少？","ALL",
                "还款申请不等于到账。模拟结果确认后才按账单分配本金；待处理、未知或失败状态不能被当作成功。重复结果不会重复入账。退款首先冲减尚未偿还本金，超出本金的部分进入独立模拟现金应退流程。系统未连接真实支付或退款渠道。"));
        rows.add(seed("5","KNOWLEDGE","附件可以包含哪些内容？","ALL",
                "仅支持PNG、JPEG、GIF、WebP、PDF、UTF-8纯文本和MP4。单文件最多512KiB，每次最多5个且合计不超过1MiB。附件使用服务器分配的标识，不接受外部网址、路径或HTML/SVG。上传者在关联前可读取；关联后按客服会话或工单当前权限读取。附件状态为UNSCANNED_SIMULATION，文件签名验证不能代替病毒扫描。请仅上传必要的合成测试材料。"));
        rows.add(seed("6","UPDATE_LOG","源码能力记录：客服资料与隔离附件","ALL",
                "记录性质：SOURCE_IMPLEMENTATION，非发布或部署证明。\n当前源码增加静态操作手册与知识资料、可版本化的自定义资料、不可变资料历史、非敏感AI/支付展示元数据，以及有身份范围和容量约束的模拟附件。\n持久化沿用现有MySQL模拟JSON聚合；未引入生产对象存储、恶意软件扫描、真实支付或外部AI。\n历史v0.2说明见仓库CHANGELOG.md；该历史版本已说明餐饮前端本地模拟与Java接线的限制。本记录不宣称已部署、已通过生产验收或已接通全部前端。"));
        return rows;
    }
    private static Map<String,Object> seed(String id,String kind,String title,String audience,String content) {
        return map("id",id,"kind",kind,"title",title,"content",content,"audience",audience,"status","ACTIVE","version",1L,"createdAt",SEED_DATE,"updatedAt",SEED_DATE,"readOnly",true,"environment","SIMULATION","source","SOURCE_IMPLEMENTATION");
    }
}
