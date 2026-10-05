package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.apache.catalina.startup.Tomcat;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback sockets and real servlet filter, with a clearly test-only durable file store. */
class SupportHttpIntegrationTest {
    final ObjectMapper json=new ObjectMapper();int port;
    Map<String,Object> request(String method,String path,String token,Map<String,Object> body,int expected)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/v1"+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(3000);c.setReadTimeout(10000);c.setRequestProperty("Idempotency-Key",UUID.randomUUID().toString());
        if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);if(!"GET".equals(method)){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(json.writeValueAsBytes(body));}}
        int status=c.getResponseCode();InputStream stream=status>=400?c.getErrorStream():c.getInputStream();Map<String,Object> response=json.readValue(stream,Map.class);assertEquals(expected,status,response.toString());assertEquals("no-store",c.getHeaderField("Cache-Control"));assertEquals("nosniff",c.getHeaderField("X-Content-Type-Options"));c.disconnect();return response;
    }
    Map<String,Object> data(String method,String path,String token,Map<String,Object> body)throws Exception{return (Map<String,Object>)request(method,path,token,body,200).get("data");}
    String login(String phone)throws Exception{Map<String,Object> challenge=data("POST","/auth/sms-challenges",null,map("phone",phone,"purpose","LOGIN"));return s(data("POST","/auth/sessions",null,map("phone",phone,"provider","PHONE_OTP","challengeId",s(challenge,"challengeId"),"code","246810")),"accessToken");}
    @Test void fullHttpShopFlowPersistsAcrossServletRestartAndRejectsLeaks()throws Exception{
        Path file=Files.createTempDirectory("support-http-test-").resolve("state.json");EngineContractTest.F seed=new EngineContractTest.F();seed.setup();seed.store.state.records.remove("rateLimits");
        SupportHttpFixture.AtomicFileStore store=new SupportHttpFixture.AtomicFileStore(file,seed.store.state);Tomcat server=SupportHttpFixture.start(0,store);
        try{
            port=server.getConnector().getLocalPort();request("GET","/support/context",null,map(),401);
            String user=login("SIM-USER-001"),owner=login("SIM-OWNER-001"),other=login("SIM-USER-002"),platform=login("SIM-SUPPORT-001");
            Map<String,Object> context=data("GET","/support/context",user,map());String shop=s(((List<Map<String,Object>>)context.get("shops")).get(0),"id");
            Map<String,Object> session=data("POST","/support/open-session",user,map("unitId",shop));String sessionId=s(session,"id");
            Map<String,Object> fileDto=data("POST","/support/attachments/upload",user,map("name","question.txt","mime","text/plain","base64",Base64.getEncoder().encodeToString("synthetic support question".getBytes(StandardCharsets.UTF_8))));String attachmentId=s(fileDto,"id");
            Map<String,Object> message=data("POST","/support/send-message",user,map("sessionId",sessionId,"text","来自HTTP的顾客消息","attachmentIds",Arrays.asList(attachmentId)));
            assertEquals(1,n((Map)data("GET","/support/session?id="+sessionId,owner,map()).get("session"),"unread"));
            data("POST","/support/mark-read",owner,map("sessionId",sessionId,"lastSeenMessageId",s(message,"id")));
            data("POST","/support/send-message",owner,map("sessionId",sessionId,"text","服务端回复"));
            request("GET","/support/session?id="+sessionId,other,map(),404);request("GET","/support/attachments/download?id="+attachmentId,other,map(),404);
            assertEquals("text/plain",s(data("GET","/support/attachments/download?id="+attachmentId,owner,map()),"mime"));
            Map<String,Object> ticket=data("POST","/support/create-ticket",user,map("unitId",shop,"title","HTTP留言","content","服务器持久化验证","priority","MEDIUM"));String ticketId=s(ticket,"id");
            data("POST","/support/reply-ticket",owner,map("ticketId",ticketId,"version",1,"text","商家真实API回复"));
            assertEquals("NOT_CONNECTED",s(data("GET","/support/platform/ai",platform,map()),"status"));request("GET","/support/platform/ai",user,map(),403);
            request("POST","/support/send-message",user,map("sessionId",sessionId,"text","伪造","senderId","1"),422);
            server.stop();server.destroy();server=SupportHttpFixture.start(0,new SupportHttpFixture.AtomicFileStore(file,SimulationSeed.create(java.time.Instant.now())));port=server.getConnector().getLocalPort();
            Map<String,Object> recovered=data("GET","/support/ticket?id="+ticketId,user,map());assertEquals(2,n(recovered,"version"));assertEquals(1,((List)recovered.get("replies")).size());
            Map<String,Object> recoveredSession=data("GET","/support/session?id="+sessionId,user,map());assertEquals(2,((List)recoveredSession.get("messages")).size());assertEquals(1,n((Map)recoveredSession.get("session"),"unread"));
        }finally{server.stop();server.destroy();}
    }
}
