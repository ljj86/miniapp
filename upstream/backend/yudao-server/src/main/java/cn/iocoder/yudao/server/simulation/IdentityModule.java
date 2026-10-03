package cn.iocoder.yudao.server.simulation;

import java.time.*;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Mock identity lifecycle. Accepts only synthetic SIM-* targets; never sends SMS. */
public final class IdentityModule implements SimModule {
    private static final Set<String> OPS=new HashSet<>(Arrays.asList("createSmsChallenge","registerUser","createSession","refreshSession","logoutSession","getBootstrap","getCurrentUser","bindWechat","recordConsent","activateSimulation","getLimitAccount","rebindPhone","grantScope","reviewScopeGrant","getHealth"));
    public boolean supports(String op){return OPS.contains(op);}
    public Object execute(String op,SimContext c,Map<String,Object> b,Map<String,String> p){
        switch(op){
            case "createSmsChallenge":return challenge(c,b);
            case "registerUser":return register(c,b);
            case "createSession":return login(c,b);
            case "refreshSession":return refresh(c,b);
            case "logoutSession":
                for(Map<String,Object> x:c.all("sessions"))if(c.actorId().equals(s(x,"userId")) && Objects.equals(p.get("_accessHash"),s(x,"accessHash")))x.put("revoked",true);
                return map("accepted",true);
            case "getBootstrap":
                Map<String,Object> boot=map("environment","SIMULATION","simulationEnabled",!bool(c.state.meta,"emergencyStopped"),"liveBusinessEnabled",false,"ruleCode",s(c.state.meta,"ruleCode"));
                if(p.get("_actorId")!=null)boot.put("user",userDto(c,c.actor()));return boot;
            case "getHealth":return map("status","UP","environment","SIMULATION","version","simulation-1.0-dev");
            case "getCurrentUser":return userDto(c,c.actor());
            case "bindWechat":throw new SimException(503,"SERVICE_UNAVAILABLE","微信身份服务未配置；当前仅开放隔离Mock身份线");
            case "recordConsent":return consent(c,b);
            case "activateSimulation":
                c.check("SIMULATION".equals(s(b,"purpose")),422,"VALIDATION_FAILED","必须明确接受模拟参与协议");consent(c,b);
                Map<String,Object> account=null;for(Map<String,Object> a:c.all("accounts"))if(c.actorUid().equals(s(a,"userUid")))account=a;
                if(account==null){long quota=n(CommerceModule.currentRule(c),"quotaMinor");account=c.create("accounts",map("userUid",c.actorUid(),"totalMinor",quota,"reservedMinor",0L,"principalMinor",0L,"availableMinor",quota,"blocked",false,"activated",true));}
                account.put("activated",true);return CommerceModule.accountDto(c,account);
            case "getLimitAccount":return CommerceModule.accountDto(c,CommerceModule.account(c,c.actorUid()));
            case "rebindPhone":return rebind(c,b,p);
            case "grantScope":return grant(c,b);
            case "reviewScopeGrant":return reviewGrant(c,b,p);
            default:throw new IllegalArgumentException(op);
        }
    }
    private Object challenge(SimContext c,Map<String,Object> b){
        String phone=s(b,"phone"),purpose=s(b,"purpose");synthetic(c,phone);String lookup=c.hash(phone.toUpperCase(Locale.ROOT));long count=0;
        for(Map<String,Object> r:c.all("challenges"))if(lookup.equals(s(r,"phoneLookup"))){
            Instant created=Instant.parse(s(r,"createdAt"));if(created.plusSeconds(86400).isAfter(c.now()))count++;
            c.check(!created.plusSeconds(60).isAfter(c.now()),429,"RATE_LIMITED","同一模拟身份每分钟只能请求一次验证码");
        }
        c.check(count<10,429,"RATE_LIMITED","该模拟身份今日验证码请求已达上限");String id=UUID.randomUUID().toString();
        c.create("challenges",map("id",id,"phoneLookup",lookup,"purpose",purpose,"codeHash",c.hash(id+":246810"),"expiresAt",c.now().plusSeconds(300).toString(),"attempts",0L,"used",false));
        return map("challengeId",id,"expiresAt",c.now().plusSeconds(300).toString(),"retryAfterSeconds",60);
    }
    private Map<String,Object> verifyChallenge(SimContext c,Map<String,Object> b,String purpose){
        Map<String,Object> challenge=c.state.table("challenges").get(s(b,"challengeId"));
        c.check(challenge!=null,422,"VALIDATION_FAILED","模拟验证请求无效");
        c.check(purpose.equals(s(challenge,"purpose")) && c.hash(s(b,"phone").toUpperCase(Locale.ROOT)).equals(s(challenge,"phoneLookup")),422,"VALIDATION_FAILED","验证码用途或身份不匹配");
        c.check(!bool(challenge,"used") && n(challenge,"attempts")<5 && c.now().isBefore(Instant.parse(s(challenge,"expiresAt"))),410,"CREDENTIAL_EXPIRED","验证码已过期、已使用或已锁定");
        if(!c.hash(s(challenge,"id")+":"+s(b,"code")).equals(s(challenge,"codeHash"))){challenge.put("attempts",n(challenge,"attempts")+1);throw new SimException(422,"VALIDATION_FAILED","模拟验证码不正确",true);}
        challenge.put("used",true);return challenge;
    }
    private Object register(SimContext c,Map<String,Object> b){
        synthetic(c,s(b,"phone"));c.check(Objects.equals(s(c.state.meta,"privacyVersion"),s(b,"privacyVersion")),422,"VALIDATION_FAILED","需确认当前模拟隐私告知版本");
        Map<String,Object> challenge=verifyChallenge(c,b,"REGISTER");String lookup=s(challenge,"phoneLookup");
        c.check(findByPhone(c,lookup)==null,409,"STATE_CONFLICT","此模拟身份已注册，请登录；不会自动合并账户");
        String id=c.id();Map<String,Object> user=c.create("users",map("id",id,"uid",String.format("y%08d",Long.parseLong(id)),"displayName",s(b,"displayName"),"phoneLookup",lookup,"phoneMasked",mask(s(b,"phone")),"status","ACTIVE","fixture",false,"upstreamUserId",null));
        c.create("consents",map("userId",id,"purpose","REGISTER","documentVersion",s(b,"privacyVersion"),"accepted",true));return session(c,user,null);
    }
    private Object login(SimContext c,Map<String,Object> b){
        c.check("PHONE_OTP".equals(s(b,"provider")),503,"SERVICE_UNAVAILABLE","真实微信登录尚未配置，当前仅支持Mock身份");
        synthetic(c,s(b,"phone"));Map<String,Object> challenge=verifyChallenge(c,b,"LOGIN");Map<String,Object> user=findByPhone(c,s(challenge,"phoneLookup"));
        c.check(user!=null && "ACTIVE".equals(s(user,"status")),401,"AUTH_REQUIRED","模拟账户不存在或已停用");return session(c,user,null);
    }
    private Object refresh(SimContext c,Map<String,Object> b){
        String hash=c.hash(s(b,"refreshToken"));Map<String,Object> old=null;
        for(Map<String,Object> r:c.all("sessions"))if(hash.equals(s(r,"refreshHash"))){old=r;break;}
        c.check(old!=null,401,"AUTH_REQUIRED","刷新凭证无效");String family=s(old,"familyId");
        if(bool(old,"rotated") || bool(old,"revoked")){
            for(Map<String,Object> r:c.all("sessions"))if(family.equals(s(r,"familyId")))r.put("revoked",true);
            throw new SimException(401,"AUTH_REQUIRED","检测到刷新凭证重放，该会话族已全部撤销",true);
        }
        c.check(c.now().isBefore(Instant.parse(s(old,"refreshExpiresAt"))),401,"AUTH_REQUIRED","刷新凭证已过期");Map<String,Object> user=c.get("users",s(old,"userId"));
        c.check("ACTIVE".equals(s(user,"status")),401,"AUTH_REQUIRED","账户已停用");old.put("rotated",true);old.put("revoked",true);return session(c,user,family);
    }
    private Map<String,Object> session(SimContext c,Map<String,Object> user,String family){
        String access=c.token(),refresh=c.token();
        c.create("sessions",map("userId",s(user,"id"),"familyId",family==null?UUID.randomUUID().toString():family,"accessHash",c.hash(access),"refreshHash",c.hash(refresh),"accessExpiresAt",c.now().plusSeconds(900).toString(),"refreshExpiresAt",c.now().plusSeconds(86400).toString(),"revoked",false,"rotated",false));
        return map("accessToken",access,"refreshToken",refresh,"expiresIn",900,"user",userDto(c,user));
    }
    public static String authenticate(SimContext c,String token){
        if(token==null || token.isEmpty())return null;String hash=c.hash(token);
        for(Map<String,Object> r:c.all("sessions"))if(hash.equals(s(r,"accessHash")) && !bool(r,"revoked") && c.now().isBefore(Instant.parse(s(r,"accessExpiresAt")))){
            Map<String,Object> user=c.get("users",s(r,"userId"));if("ACTIVE".equals(s(user,"status")))return s(user,"id");
        }
        throw new SimException(401,"AUTH_REQUIRED","会话已过期或已撤销，请重新登录");
    }
    private Object consent(SimContext c,Map<String,Object> b){
        c.actorId();String expected="SIMULATION".equals(s(b,"purpose"))?s(c.state.meta,"agreementVersion"):s(c.state.meta,"privacyVersion");
        c.check(bool(b,"accepted") && expected.equals(s(b,"documentVersion")),422,"VALIDATION_FAILED","必须主动确认当前版本的模拟告知");
        for(Map<String,Object> r:c.all("consents"))if(c.actorId().equals(s(r,"userId")) && expected.equals(s(r,"documentVersion")) && Objects.equals(s(b,"purpose"),s(r,"purpose")))return map("accepted",true,"resourceId",s(r,"id"));
        Map<String,Object> r=c.create("consents",map("userId",c.actorId(),"purpose",s(b,"purpose"),"documentVersion",expected,"accepted",true));return map("accepted",true,"resourceId",s(r,"id"));
    }
    private Object rebind(SimContext c,Map<String,Object> b,Map<String,String> p){
        synthetic(c,s(b,"phone"));c.check(Objects.equals(c.hash(s(b,"reauthProof")),p.get("_accessHash")),401,"AUTH_REQUIRED","请以当前有效会话完成原账户再验证");
        Map<String,Object> ch=verifyChallenge(c,b,"REBIND");Map<String,Object> existing=findByPhone(c,s(ch,"phoneLookup"));
        c.check(existing==null || c.actorId().equals(s(existing,"id")),409,"STATE_CONFLICT","新身份已被其他账户使用，不能自动合并");
        Map<String,Object> user=c.actor();user.put("phoneLookup",s(ch,"phoneLookup"));user.put("phoneMasked",mask(s(b,"phone")));c.bump(user);
        for(Map<String,Object> session:c.all("sessions"))if(c.actorId().equals(s(session,"userId")))session.put("revoked",true);return userDto(c,user);
    }
    private Object grant(SimContext c,Map<String,Object> b){
        c.requireRoles("SECURITY");Map<String,Object> user=null;for(Map<String,Object> u:c.all("users"))if(s(b,"userUid").equals(s(u,"uid")))user=u;
        c.check(user!=null,404,"RESOURCE_NOT_FOUND","目标身份不存在");String scope=s(b,"scopeKey");
        Map<String,Object> data=map("userId",s(user,"id"),"role",s(b,"roleCode"),"requesterId",c.actorId(),"status","PENDING","reason",s(b,"reason"),"scopeKey",scope,"expiresAt",b.get("expiresAt"));
        if(scope.startsWith("MERCHANT:")){String uid=scope.substring(9);merchant(c,uid);data.put("merchantUid",uid);}
        else if(scope.startsWith("STORE:")){Map<String,Object> st=c.get("stores",scope.substring(6));data.put("merchantUid",s(st,"merchantUid"));data.put("storeId",s(st,"id"));}
        else c.check("PLATFORM".equals(scope),422,"VALIDATION_FAILED","scopeKey必须为PLATFORM、MERCHANT:uid或STORE:id");
        c.check(!Arrays.asList("OWNER","CLERK").contains(s(b,"roleCode")) || !"PLATFORM".equals(scope),422,"VALIDATION_FAILED","商家角色不能授予全平台范围");
        if(b.get("expiresAt")!=null)c.check(c.now().isBefore(Instant.parse(s(b,"expiresAt"))),422,"VALIDATION_FAILED","授权截止时间必须在将来");
        Map<String,Object> r=c.create("adminRequests",data);return map("accepted",true,"resourceId",s(r,"id"));
    }
    private Object reviewGrant(SimContext c,Map<String,Object> b,Map<String,String> p){
        c.requireRoles("SECURITY");Map<String,Object> r=c.get("adminRequests",p.get("id"));c.version(r,b);c.independent(s(r,"requesterId"));c.check("PENDING".equals(s(r,"status")),409,"STATE_CONFLICT","授权申请已处理");
        c.check(!c.actorId().equals(s(r,"userId")),403,"SELF_REVIEW_DENIED","不能批准授予自己的权限");
        r.put("status","APPROVE".equals(s(b,"decision"))?"APPROVED":"REJECTED");r.put("checkerId",c.actorId());r.put("reviewReason",s(b,"reason"));c.bump(r);
        if("APPROVED".equals(s(r,"status"))){
            c.create("grants",map("userId",s(r,"userId"),"role",s(r,"role"),"merchantUid",r.get("merchantUid"),"storeId",r.get("storeId"),"bookId",null,"validFrom",c.now().toString(),"validTo",r.get("expiresAt"),"status","ACTIVE","approvalId",s(r,"id")));
            c.state.meta.put("grantVersion",n(c.state.meta,"grantVersion")+1);
        }
        return map("accepted",true,"resourceId",s(r,"id"));
    }
    public static Map<String,Object> userDto(SimContext c,Map<String,Object> u){return c.project(u,"id","uid","displayName","phoneMasked","status","version");}
    public static Map<String,Object> merchant(SimContext c,String uid){for(Map<String,Object> r:c.all("merchants"))if(uid.equals(s(r,"uid")))return r;throw new SimException(404,"RESOURCE_NOT_FOUND","模拟商家不存在");}
    private static Map<String,Object> findByPhone(SimContext c,String lookup){for(Map<String,Object> u:c.all("users"))if(lookup.equals(s(u,"phoneLookup")))return u;return null;}
    private static void synthetic(SimContext c,String phone){c.check(phone!=null && phone.matches("SIM-[A-Z0-9-]{3,28}"),422,"VALIDATION_FAILED","当前仅接受SIM-开头的合成测试身份，不接收真实手机号");}
    private static String mask(String phone){return phone.length()<7?"SIM-***":phone.substring(0,4)+"***"+phone.substring(phone.length()-3);}
}
