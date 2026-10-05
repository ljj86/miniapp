package cn.iocoder.yudao.server.simulation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;
class SupportProfileTest {
    @Test void displayProfileIsPrivateVersionedAndCannotChangeIdentity()throws Exception{
        ShopSupportTest.Fixture f=new ShopSupportTest.Fixture();f.ready();ObjectMapper json=new ObjectMapper();String identity=json.writeValueAsString(f.store.state.table("users"));
        Map<String,Object> body=map("version",1,"nickname","合成昵称","avatarUrl","/avatar.svg","email","synthetic@example.invalid","contactPhone","+00 123456");
        Map<String,Object> changed=f.action("profile",f.user,body);assertEquals(2,n(changed,"version"));assertEquals("合成昵称",s(changed,"nickname"));assertEquals(identity,json.writeValueAsString(f.store.state.table("users")));
        assertEquals("",s(f.query("profile",f.other),"email"));assertEquals("synthetic@example.invalid",s(f.query("profile",f.user),"email"));
        Map<String,Object> session=f.open();assertEquals("合成昵称",s(session,"customerName"));assertFalse(session.containsKey("email"));ShopSupportTest.failure(409,()->f.action("profile",f.user,body));
        body.put("version",2);body.put("uid","attacker");ShopSupportTest.failure(422,()->f.action("profile",f.user,body));body.remove("uid");body.put("avatarUrl","https://example.invalid/tracker.png");ShopSupportTest.failure(422,()->f.action("profile",f.user,body));
        assertEquals("/avatar.svg",s(f.query("profile",f.user),"avatarUrl"));
    }
}
