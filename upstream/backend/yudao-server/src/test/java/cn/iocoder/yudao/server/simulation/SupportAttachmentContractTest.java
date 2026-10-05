package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

public class SupportAttachmentContractTest {
    private static SimContext context(EngineContractTest.F f,String actor) { return new SimContext(f.store.state,actor,f.clock.instant()); }
    private static Map<String,String> id(String value) { Map<String,String> p=new LinkedHashMap<>();p.put("id",value);return p; }
    @SuppressWarnings("unchecked") private static Map<String,Object> execute(String op,SimContext c,Map<String,Object> b,Map<String,String> p) {return (Map<String,Object>)SupportAttachments.execute(op,c,b,p);}
    private static Map<String,Object> upload(SimContext c,String name,String mime,byte[] bytes) {return execute("attachments.upload",c,map("name",name,"mime",mime,"base64",Base64.getEncoder().encodeToString(bytes)),new LinkedHashMap<>());}
    private static Map<String,Object> text(SimContext c,String text) {return upload(c,"模拟说明.txt","text/plain",text.getBytes(StandardCharsets.UTF_8));}
    private static String ticket(SimContext c) {return s(c.create("supportTickets",map("userId",c.actorId(),"unitId","777","merchantUid","s00001","archived",false,"status","OPEN")),"id");}
    private static byte[] image(String format) throws Exception {BufferedImage b=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(ImageIO.write(b,format,out));return out.toByteArray();}

    @Test public void allSupportedTypesHaveMetadataOnlyAndCanonicalHash() throws Exception {
        EngineContractTest.F f=new EngineContractTest.F();SimContext c=context(f,"101");
        Map<String,byte[]> types=new LinkedHashMap<>();types.put("image/png",image("png"));types.put("image/jpeg",image("jpeg"));types.put("image/gif",image("gif"));
        byte[] webp=ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(18).put("WEBPVP8L".getBytes(StandardCharsets.US_ASCII)).putInt(5).put(new byte[]{47,0,0,0,0,0}).array();types.put("image/webp",webp);
        types.put("application/pdf","%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));types.put("text/plain","真实UTF-8模拟文本\n".getBytes(StandardCharsets.UTF_8));
        byte[] mp4=ByteBuffer.allocate(36).putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("isommp41".getBytes(StandardCharsets.US_ASCII)).putInt(12).put("mdat".getBytes(StandardCharsets.US_ASCII)).putInt(0).array();types.put("video/mp4",mp4);
        for(Map.Entry<String,byte[]> entry:types.entrySet()) {
            Map<String,Object> file=upload(c,"evidence.bin",entry.getKey(),entry.getValue());assertEquals(entry.getValue().length,n(file,"size"));assertFalse(file.containsKey("base64"));assertFalse(file.containsKey("uploaderId"));assertFalse(file.containsKey("path"));assertFalse(file.containsKey("url"));
            assertEquals("UNSCANNED_SIMULATION",s(file,"securityStatus"));assertTrue(s(file,"id").matches("[1-9][0-9]*"));assertTrue(s(file,"sha256").matches("[0-9a-f]{64}"));
            Map<String,Object> downloaded=execute("attachments.download",c,map(),id(s(file,"id")));assertArrayEquals(entry.getValue(),Base64.getDecoder().decode(s(downloaded,"base64")));assertEquals(s(file,"sha256"),s(downloaded,"sha256"));
            assertFalse(SupportAttachments.describe(c,Arrays.asList(s(file,"id"))).get(0).containsKey("base64"));
        }
        assertEquals(7,f.store.state.table("supportAttachments").size());
    }
    @Test public void unboundPrivateBoundScopeAndArchiveRulesAreRechecked() throws Exception {
        EngineContractTest.F f=new EngineContractTest.F();SimContext customer=context(f,"101"),other=context(f,"102"),merchant=context(f,"201"),platform=context(f,"501");
        Map<String,Object> grant=merchant.create("grants",map("userId","201","role","OWNER","merchantUid","s00001","storeId","777","status","ACTIVE"));
        Map<String,Object> file=text(customer,"customer-owned");String fileId=s(file,"id");
        for(SimContext outsider:Arrays.asList(other,merchant,platform))assertEquals(404,assertThrows(SimException.class,()->execute("attachments.download",outsider,map(),id(fileId))).status);
        String ticket=ticket(customer);SupportAttachments.attach(customer,Arrays.asList(fileId),"ticket",ticket);
        assertEquals("customer-owned",new String(Base64.getDecoder().decode(s(execute("attachments.download",merchant,map(),id(fileId)),"base64")),StandardCharsets.UTF_8));
        execute("attachments.download",platform,map(),id(fileId));assertEquals(404,assertThrows(SimException.class,()->execute("attachments.download",other,map(),id(fileId))).status);
        assertEquals(404,assertThrows(SimException.class,()->SupportAttachments.attach(merchant,Arrays.asList(fileId),"ticket",ticket)).status);
        grant.put("status","REVOKED");
        assertEquals(404,assertThrows(SimException.class,()->execute("attachments.download",merchant,map(),id(fileId))).status);
        grant.put("status","ACTIVE");
        f.store.state.table("supportTickets").get(ticket).put("archived",true);
        assertEquals(404,assertThrows(SimException.class,()->execute("attachments.download",merchant,map(),id(fileId))).status);
        execute("attachments.download",customer,map(),id(fileId));execute("attachments.download",platform,map(),id(fileId));
        String second=ticket(customer);assertEquals("ATTACHMENT_ALREADY_BOUND",assertThrows(SimException.class,()->SupportAttachments.attach(customer,Arrays.asList(fileId),"ticket",second)).code);
        SimState restored=new ObjectMapper().readValue(new ObjectMapper().writeValueAsBytes(f.store.state),SimState.class);
        assertEquals("ticket",s(SupportAttachments.describe(new SimContext(restored,"101",f.clock.instant()),Arrays.asList(fileId)).get(0),"resourceType"));
        assertEquals(401,assertThrows(SimException.class,()->execute("attachments.download",context(f,null),map(),id(fileId))).status);
    }
    @Test public void malformedEncodingPathsUnknownFieldsAndActiveMarkupAreRejected() throws Exception {
        EngineContractTest.F f=new EngineContractTest.F();SimContext c=context(f,"101");
        for(String base64:Arrays.asList("Zg","Zg==\n","Zg=","Zh==","data:text/plain;base64,Zg==","_-8=",""))assertEquals(422,assertThrows(SimException.class,()->execute("attachments.upload",c,map("name","ok.txt","mime","text/plain","base64",base64),new LinkedHashMap<>())).status);
        for(String name:Arrays.asList("../x.txt","a/b.txt","a\\b.txt","x.html","x.svg","x.js","C:file.txt","x%2F.txt","x\n.txt","x\u202Etxt"," file.txt",".hidden","file.txt."))assertEquals(422,assertThrows(SimException.class,()->upload(c,name,"text/plain",new byte[]{65})).status);
        for(String markup:Arrays.asList("<html>doc</html>","<svg/>","<script>alert(1)</script>","<!DOCTYPE HTML><body>x</body>"))assertEquals(422,assertThrows(SimException.class,()->text(c,markup)).status);
        assertEquals(422,assertThrows(SimException.class,()->upload(c,"fake.txt","text/plain",new byte[]{(byte)0xff,0})).status);
        assertEquals(422,assertThrows(SimException.class,()->upload(c,"fake.png","image/png","not-an-image".getBytes(StandardCharsets.UTF_8))).status);
        assertEquals(422,assertThrows(SimException.class,()->upload(c,"fake.jpg","image/jpeg",new byte[]{(byte)255,(byte)216,(byte)255,1,1,1,1,1,1,1,(byte)255,(byte)217})).status);
        byte[] png=image("png");png[png.length-1]^=1;assertEquals(422,assertThrows(SimException.class,()->upload(c,"bad.png","image/png",png)).status);
        assertEquals(422,assertThrows(SimException.class,()->upload(c,"fake.svg","image/svg+xml","<svg/>".getBytes(StandardCharsets.UTF_8))).status);
        assertEquals(422,assertThrows(SimException.class,()->execute("attachments.upload",c,map("name","x.txt","mime","text/plain","base64","eA==","url","https://example.invalid"),new LinkedHashMap<>())).status);
        assertEquals(0,f.store.state.table("supportAttachments").size());
    }
    @Test public void fileBindingCountAndDecodedByteLimitsAreEnforcedBeforeMutation() {
        EngineContractTest.F f=new EngineContractTest.F();SimContext c=context(f,"101");String resource=ticket(c);
        byte[] max=new byte[SupportAttachments.MAX_FILE_BYTES];Arrays.fill(max,(byte)'a');
        List<String> ids=new ArrayList<>();for(int i=0;i<3;i++)ids.add(s(upload(c,"big.txt","text/plain",max),"id"));
        assertEquals("ATTACHMENTS_TOO_LARGE",assertThrows(SimException.class,()->SupportAttachments.attach(c,ids,"ticket",resource)).code);
        for(String file:ids)assertNull(f.store.state.table("supportAttachments").get(file).get("resourceType"));
        assertEquals(2,SupportAttachments.attach(c,ids.subList(0,2),"ticket",resource).size());
        assertEquals(1,SupportAttachments.attach(c,ids.subList(2,3),"ticket",resource).size()); // per-command, not session lifetime
        assertEquals(422,assertThrows(SimException.class,()->SupportAttachments.attach(c,Arrays.asList(ids.get(0),ids.get(0)),"ticket",resource)).status);
        List<String> small=new ArrayList<>();for(int i=0;i<6;i++)small.add(s(text(c,"x"),"id"));
        assertEquals(422,assertThrows(SimException.class,()->SupportAttachments.attach(c,small,"ticket",resource)).status);
        assertEquals(5,SupportAttachments.attach(c,small.subList(0,5),"ticket",resource).size());
        byte[] over=new byte[max.length+1];Arrays.fill(over,(byte)'a');assertEquals(422,assertThrows(SimException.class,()->upload(c,"too-big.txt","text/plain",over)).status);
        assertEquals(422,assertThrows(SimException.class,()->upload(c,"empty.txt","text/plain",new byte[0])).status);
        String unbound=small.get(5);assertEquals(404,assertThrows(SimException.class,()->SupportAttachments.attach(c,Arrays.asList(unbound),"ticket","999999")).status);assertNull(f.store.state.table("supportAttachments").get(unbound).get("resourceType"));
    }
    @Test public void storedBytesCountTowardActorAndAggregateQuota() {
        EngineContractTest.F f=new EngineContractTest.F();SimContext c=context(f,"101");
        Map<String,Object> row=c.create("supportAttachments",map("uploaderId","101","size",8L*1024*1024-1));
        text(c,"x");assertEquals("ATTACHMENT_QUOTA_EXCEEDED",assertThrows(SimException.class,()->text(c,"x")).code);
        row.put("uploaderId","102");row.put("size",32L*1024*1024-1);
        assertEquals("ATTACHMENT_QUOTA_EXCEEDED",assertThrows(SimException.class,()->text(c,"x")).code);
    }
    @Test public void tinyUploadsCannotGrowMetadataWithoutBound() {
        EngineContractTest.F f=new EngineContractTest.F();SimContext c=context(f,"101");
        for(int i=0;i<127;i++)c.create("supportAttachments",map("uploaderId","101","size",1L));
        text(c,"x");assertEquals(128,f.store.state.table("supportAttachments").size());
        assertEquals("ATTACHMENT_QUOTA_EXCEEDED",assertThrows(SimException.class,()->text(c,"x")).code);
        EngineContractTest.F g=new EngineContractTest.F();SimContext global=context(g,"101");
        for(int i=0;i<1023;i++)global.create("supportAttachments",map("uploaderId","102","size",1L));
        text(global,"x");assertEquals(1024,g.store.state.table("supportAttachments").size());
        assertEquals("ATTACHMENT_QUOTA_EXCEEDED",assertThrows(SimException.class,()->text(global,"x")).code);
    }
    @Test public void engineRejectsUnknownFieldsAndUploadsReplayWithoutDuplicatingBytes() throws Exception {
        EngineContractTest.F f=new EngineContractTest.F();String token=f.login("SIM-USER-001");String key="same-attachment-request-001";
        Map<String,Object> body=map("name","api.txt","mime","text/plain","base64","aGVsbG8=");
        Map<String,Object> first=f.req("POST","/api/v1/support/attachments/upload",token,body,key,new LinkedHashMap<>());
        Map<String,Object> second=f.req("POST","/api/v1/support/attachments/upload",token,body,key,new LinkedHashMap<>());
        assertEquals(new ObjectMapper().writeValueAsString(first),new ObjectMapper().writeValueAsString(second));assertEquals(1,f.store.state.table("supportAttachments").size());
        Map<String,Object> bad=new LinkedHashMap<>(body);bad.put("uploaderId","102");assertEquals(422,assertThrows(SimException.class,()->f.data("POST","/api/v1/support/attachments/upload",token,bad)).status);
    }
}
