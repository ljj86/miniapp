package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Transaction-local domain helpers. All timestamps use the server clock. */
public final class SimContext {
    public final SimState state;
    private final String userId;
    private final Instant instant;
    private static final ObjectMapper JSON = new ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private static final SecureRandom RANDOM = new SecureRandom();
    public SimContext(SimState state, String userId, Instant instant) {
        this.state = state; this.userId = userId; this.instant = instant;
    }
    public Instant now() { return instant; }
    public String actorId() { check(userId != null,401,"AUTH_REQUIRED","请先登录模拟账户"); return userId; }
    public Map<String,Object> actor() { return get("users",actorId()); }
    public String actorUid() { return s(actor(),"uid"); }
    public String id() { return Long.toString(++state.sequence); }
    public Map<String,Object> create(String kind, Map<String,Object> data) {
        Map<String,Object> r = new LinkedHashMap<>(data);
        if (!r.containsKey("id")) r.put("id",id());
        check(!state.table(kind).containsKey(s(r,"id")),409,"DUPLICATE_RESOURCE","资源已存在");
        if (!r.containsKey("version")) r.put("version",1L);
        if (!r.containsKey("createdAt")) r.put("createdAt",now().toString());
        r.put("updatedAt",now().toString()); state.table(kind).put(s(r,"id"),r); return r;
    }
    public Map<String,Object> get(String kind,String id) {
        Map<String,Object> r = state.table(kind).get(id);
        check(r!=null,404,"RESOURCE_NOT_FOUND","资源不存在或不可访问"); return r;
    }
    public List<Map<String,Object>> all(String kind) { return new ArrayList<>(state.table(kind).values()); }
    public void bump(Map<String,Object> r) { r.put("version",Math.addExact(n(r,"version"),1)); r.put("updatedAt",now().toString()); }
    public void version(Map<String,Object> r,Map<String,Object> b) {
        check(n(r,"version")==n(b,"version"),409,"VERSION_CONFLICT","数据已更新，请重新读取后确认");
    }
    public boolean hasRole(String role) {
        if(userId==null) return false;
        if("USER".equals(role)) return "ACTIVE".equals(s(actor(),"status"));
        if(!"ACTIVE".equals(s(actor(),"status")))return false;
        for(Map<String,Object> g:all("grants"))if(userId.equals(s(g,"userId")) && role.equals(s(g,"role")) && "ACTIVE".equals(s(g,"status")) && (g.get("validFrom")==null || !now().isBefore(Instant.parse(s(g,"validFrom")))) && (g.get("validTo")==null || now().isBefore(Instant.parse(s(g,"validTo")))))return true;
        return false;
    }
    public void requireRoles(String... roles) {
        actorId(); for(String role:roles) if(hasRole(role)) return;
        throw new SimException(403,"SCOPE_DENIED","当前账户没有所需权限");
    }
    public boolean inScope(String merchantUid,String storeId,String bookId,String... roles) {
        if(userId==null || !"ACTIVE".equals(s(actor(),"status"))) return false;
        for(Map<String,Object> g: all("grants")) {
            if(!userId.equals(s(g,"userId")) || !"ACTIVE".equals(s(g,"status")) || !Arrays.asList(roles).contains(s(g,"role")))continue;
            if(g.get("validFrom")!=null && now().isBefore(Instant.parse(s(g,"validFrom"))))continue;
            if(g.get("validTo")!=null && !now().isBefore(Instant.parse(s(g,"validTo"))))continue;
            if(merchantUid==null && g.get("merchantUid")!=null)continue;
            if(storeId==null && g.get("storeId")!=null)continue;
            if(bookId==null && g.get("bookId")!=null)continue;
            if(merchantUid!=null && g.get("merchantUid")!=null && !merchantUid.equals(s(g,"merchantUid")))continue;
            if(storeId!=null && g.get("storeId")!=null && !storeId.equals(s(g,"storeId")))continue;
            if(bookId!=null && g.get("bookId")!=null && !bookId.equals(s(g,"bookId")))continue;
            return true;
        }
        return false;
    }
    public void requireScope(String merchantUid,String storeId,String bookId,String... roles) {
        actorId(); check(inScope(merchantUid,storeId,bookId,roles),403,"SCOPE_DENIED","资源超出当前授权范围");
    }
    public void independent(String requesterId) {
        check(requesterId!=null && !actorId().equals(requesterId),403,"SELF_REVIEW_DENIED","申请人与复核人必须是不同自然人");
    }
    public void check(boolean condition,int status,String code,String message) { if(!condition)throw new SimException(status,code,message); }
    public void notify(String userId,String title,String resourceType,String resourceId) {
        String kind;
        switch(resourceType){
            case "merchantApplication":kind="applications";break;
            case "repayment":kind="repayments";break;
            case "refund":kind="refunds";break;
            case "order":kind="orders";break;
            case "dispute":kind="disputes";break;
            case "receivable":kind="receivables";break;
            default:kind=resourceType;
        }
        Map<String,Object> resource=state.table(kind).get(resourceId);
        notify(userId,title,resourceType,resourceId,title,resource==null?1:Math.max(1,n(resource,"version")));
    }
    public void notify(String userId,String title,String resourceType,String resourceId,String template,long eventVersion) {
        NotificationModule.enqueue(this,userId,title,resourceType,resourceId,template,eventVersion);
    }
    public void audit(String action,String resourceType,String resourceId,Map detail) {
        create("audit",map("actorId",userId,"actorUid",userId==null?"ANON":s(actor(),"uid"),"requestId",state.meta.get("requestId"),"action",action,"resourceType",resourceType,"resourceId",resourceId,"detail",detail,"outcome","SUCCESS","occurredAt",now().toString()));
    }
    public String token() { byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public String hash(String value) {
        try { byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format("%02x",x));return s.toString(); }
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public String hashObject(Object value) { try{return hash(JSON.writeValueAsString(value));}catch(Exception e){throw new IllegalArgumentException(e);} }
    public Map<String,Object> project(Map<String,Object> value,String... fields) {
        Map<String,Object> out=new LinkedHashMap<>();for(String f:fields)if(value.containsKey(f) && value.get(f)!=null)out.put(f,value.get(f));return out;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> page(List<?> values,Map<String,String> params) {
        int size=positive(params.get("limit"),20);check(size<=100,422,"VALIDATION_FAILED","limit最多100");
        List<Map<String,Object>> rows=new ArrayList<>();for(Object x:values)rows.add((Map<String,Object>)x);
        boolean ascending="createdAt_asc".equals(params.get("sort"));
        rows.sort((a,b)->ascending?Long.compare(n(a,"id"),n(b,"id")):Long.compare(n(b,"id"),n(a,"id")));
        Map<String,String> filters=new TreeMap<>(params);filters.remove("cursor");
        String scope=hashObject(map("actor",userId,"filters",filters,"grantVersion",state.meta.get("grantVersion")));
        String cursor=params.get("cursor");long last=ascending?0:Long.MAX_VALUE;
        if(cursor!=null && !cursor.isEmpty())try{
            String[] parts=cursor.split("\\.",-1);check(parts.length==2 && MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8),mac(parts[0]).getBytes(StandardCharsets.UTF_8)),422,"VALIDATION_FAILED","游标签名无效");
            Map<String,Object> data=JSON.readValue(Base64.getUrlDecoder().decode(parts[0]),Map.class);
            check(scope.equals(s(data,"scope")) && n(data,"expires")>now().getEpochSecond(),422,"VALIDATION_FAILED","游标已过期或授权范围已变化");last=n(data,"last");
        }catch(SimException e){throw e;}catch(Exception e){throw new SimException(422,"VALIDATION_FAILED","无效分页游标");}
        List<Map<String,Object>> selected=new ArrayList<>();for(Map<String,Object> row:rows)if(ascending?n(row,"id")>last:n(row,"id")<last)selected.add(row);
        boolean more=selected.size()>size;List<Map<String,Object>> items=new ArrayList<>(selected.subList(0,Math.min(size,selected.size())));String next="";
        if(more)try{String body=Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(map("last",n(items.get(items.size()-1),"id"),"scope",scope,"expires",now().plusSeconds(900).getEpochSecond())));next=body+"."+mac(body);}catch(Exception e){throw new IllegalStateException(e);}
        return map("items",items,"nextCursor",next,"hasMore",more);
    }
    private String mac(String value){
        try{javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(s(state.meta,"cursorSecret").getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
    }
    private int positive(String value,int fallback){if(value==null)return fallback;try{int n=Integer.parseInt(value);check(n>0,422,"VALIDATION_FAILED","分页参数必须大于0");return n;}catch(NumberFormatException e){throw new SimException(422,"VALIDATION_FAILED","无效分页参数");}}
    public static Map<String,Object> map(Object... pairs) { if(pairs.length%2!=0)throw new IllegalArgumentException();Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m; }
    public static String str(Object value) { return value==null?null:String.valueOf(value); }
    public static String s(Map<?,?> map,String key){return str(map.get(key));}
    public static long n(Map<?,?> map,String key){Object v=map.get(key);if(v==null)return 0;if(v instanceof Number)return ((Number)v).longValue();try{return Long.parseLong(String.valueOf(v));}catch(Exception e){throw new SimException(422,"VALIDATION_FAILED",key+"必须为整数");}}
    public static boolean bool(Map<?,?> map,String key){return Boolean.TRUE.equals(map.get(key));}
    @SuppressWarnings("unchecked") public static <T> List<T> list(Map<?,?> map,String key){Object v=map.get(key);return v instanceof List?(List<T>)v:new ArrayList<T>();}
}
