package cn.iocoder.yudao.server.simulation;
import java.time.*;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Deterministic, visibly synthetic identities. No real account or approved business rule. */
public final class SimulationSeed {
    public static SimState create(Instant now){
        SimState state=new SimState();SimContext c=new SimContext(state,null,now);
        state.meta.putAll(map("environment","SIMULATION","fixtureMode",true,"fixtureWarning","隔离测试夹具，不代表正式身份、规则签批或真实业务许可","emergencyStopped",false,"privacyVersion","sim-privacy-1","agreementVersion","sim-terms-1","grantVersion",1L,"cursorSecret",c.token(),"schemaVersion",1));
        user(c,"101","SIM-USER-001","模拟消费者一");user(c,"102","SIM-USER-002","模拟消费者二");user(c,"201","SIM-OWNER-001","模拟商家申请人");user(c,"202","SIM-CLERK-001","模拟店员");
        user(c,"301","SIM-REVIEWER-001","模拟入驻复核人");role(c,"301","MERCHANT_REVIEWER");
        user(c,"401","SIM-MAKER-001","模拟账务申请人");role(c,"401","LEDGER_MAKER");
        user(c,"402","SIM-CHECKER-001","模拟账务复核人");role(c,"402","LEDGER_CHECKER");
        user(c,"501","SIM-SUPPORT-001","模拟异议处理人");role(c,"501","SUPPORT");
        user(c,"601","SIM-SECURITY-001","模拟安全管理员一");role(c,"601","SECURITY");
        user(c,"602","SIM-SECURITY-002","模拟安全管理员二");role(c,"602","SECURITY");
        user(c,"701","SIM-CONTROLLER-001","模拟结果控制员");role(c,"701","SIM_CONTROLLER");
        c.create("users",map("id","900","uid","y00000900","displayName","Mock回调服务身份","phoneLookup",c.hash(c.token()),"phoneMasked","SERVICE","status","ACTIVE","fixture",false));
        Map<String,Object> rule=c.create("rules",map("code","RV-SIM-TEST-001","status","APPROVED","quotaMinor",10000L,"transactionMaxMinor",2000L,"termDays",30L,"credentialTtlSeconds",300L,"reservationTtlSeconds",900L,"requesterId","401","checkerId","402","fixtureOnly",true,"formalApproval",false));
        state.meta.put("ruleId",s(rule,"id"));state.meta.put("ruleCode",s(rule,"code"));return state;
    }
    private static void user(SimContext c,String id,String phone,String label){c.create("users",map("id",id,"uid",String.format("y%08d",Long.parseLong(id)),"phoneLookup",c.hash(phone),"phoneMasked","SIM-***"+phone.substring(phone.length()-3),"fixturePhone",phone,"displayName",label,"status","ACTIVE","fixture",true,"upstreamUserId",null));}
    private static void role(SimContext c,String id,String role){c.create("grants",map("userId",id,"role",role,"status","ACTIVE","validFrom",c.now().minusSeconds(1).toString(),"fixtureOnly",true));}
}
