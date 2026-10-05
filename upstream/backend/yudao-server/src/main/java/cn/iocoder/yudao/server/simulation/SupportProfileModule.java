package cn.iocoder.yudao.server.simulation;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Private display profile, separate from the immutable login/finance identity. */
public final class SupportProfileModule {
    private SupportProfileModule() {}
    public static Object execute(String op,SimContext c,Map<String,Object> body){
        c.requireRoles("USER");if("profile.get".equals(op))return view(c);
        c.check("profile.update".equals(op),404,"RESOURCE_NOT_FOUND","资料操作不存在");
        Map<String,Object> current=view(c);c.version(current,body);
        String name=field(c,body,"nickname",80,false),avatar=field(c,body,"avatarUrl",120,false),email=field(c,body,"email",254,true),phone=field(c,body,"contactPhone",32,true);
        c.check("/avatar.svg".equals(avatar),422,"VALIDATION_FAILED","头像必须是受控的本站资源");
        c.check(email.isEmpty()||email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}"),422,"VALIDATION_FAILED","邮箱格式无效");
        c.check(phone.isEmpty()||phone.matches("[+0-9 ()-]{3,32}"),422,"VALIDATION_FAILED","联系号码格式无效");
        Map<String,Object> row=c.state.table("supportProfiles").get(c.actorId());if(row==null)row=c.create("supportProfiles",map("id",c.actorId(),"userId",c.actorId()));
        row.putAll(map("nickname",name,"avatarUrl",avatar,"email",email,"contactPhone",phone));c.bump(row);
        c.audit("support.profile.update","supportProfile",c.actorId(),map("fields",Arrays.asList("nickname","avatarUrl","email","contactPhone"),"version",n(row,"version")));return view(c);
    }
    static Map<String,Object> view(SimContext c){
        Map<String,Object> row=lookup(c,c.actorId());Map<String,Object> result=row==null?map("version",1L,"nickname",s(c.actor(),"displayName"),"avatarUrl","/avatar.svg","email","","contactPhone",""):c.project(row,"version","nickname","avatarUrl","email","contactPhone","updatedAt");
        result.putAll(map("id",c.actorId(),"uid",c.actorUid(),"role",SupportModule.role(c),"username",s(c.actor(),"fixturePhone"),"loginPhone",s(c.actor(),"phoneMasked"),"identityReadOnly",true,"environment","SIMULATION"));return result;
    }
    static String name(SimContext c,String userId){Map<String,Object> row=lookup(c,userId);return row==null?s(c.get("users",userId),"displayName"):s(row,"nickname");}
    private static Map<String,Object> lookup(SimContext c,String id){Map<String,Map<String,Object>> rows=c.state.records.get("supportProfiles");return rows==null?null:rows.get(id);}
    private static String field(SimContext c,Map<String,Object> body,String key,int max,boolean empty){Object raw=body.get(key);c.check(raw instanceof String,422,"VALIDATION_FAILED",key+"必须为文本");String value=((String)raw).trim();c.check((empty||!value.isEmpty())&&value.codePointCount(0,value.length())<=max,422,"VALIDATION_FAILED",key+"无效或过长");for(char x:value.toCharArray())c.check(!Character.isISOControl(x),422,"VALIDATION_FAILED",key+"不能含控制字符");return value;}
}
