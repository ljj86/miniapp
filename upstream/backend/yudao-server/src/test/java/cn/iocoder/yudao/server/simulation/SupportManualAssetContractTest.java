package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

public class SupportManualAssetContractTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static Map<String,String> params(String id,String format,String page) {Map<String,String> p=new LinkedHashMap<>();p.put("id",id);p.put("format",format);if(page!=null)p.put("page",page);return p;}
    private static SimContext actor(String id) {EngineContractTest.F f=new EngineContractTest.F();return new SimContext(f.store.state,id,f.clock.instant());}
    @SuppressWarnings("unchecked") private static Map<String,Object> asset(SimContext c,Map<String,String> p) {return (Map<String,Object>)SupportManualAssets.execute("manuals.asset",c,map(),p);}
    private static String hash(byte[] bytes) throws Exception {StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b));return s.toString();}
    private static Map<String,byte[]> fixtures() throws Exception {
        Map<String,byte[]> files=new LinkedHashMap<>();List<Map<String,Object>> manuals=new ArrayList<>();String[] names={"customer-manual","merchant-manual","platform-manual"};
        for(int i=0;i<names.length;i++) {
            List<Map<String,Object>> assets=new ArrayList<>();
            for(int page=0;page<=2;page++) {
                boolean pdf=page==0;String file=names[i]+(pdf?".pdf":"-page-"+page+".png");byte[] bytes=pdf?"%PDF-1.7\nmock testing source\n%%EOF\n".getBytes(StandardCharsets.US_ASCII):new byte[]{(byte)137,80,78,71,13,10,26,10,0,0,0,0,0,0,0,0,0,0,0,0};
                files.put(file,bytes);Map<String,Object> record=map("format",pdf?"PDF":"PNG","file",file,"mime",pdf?"application/pdf":"image/png","size",bytes.length,"sha256",hash(bytes));if(!pdf)record.put("page",page);assets.add(record);
            }
            manuals.add(map("id",Integer.toString(i+1),"title","模拟手册","assets",assets));
        }
        files.put("manifest.json",JSON.writeValueAsBytes(map("version",1,"manuals",manuals)));return files;
    }
    private static SupportManualAssets.Bundle load(Map<String,byte[]> files) {return SupportManualAssets.load(name->files.containsKey(name)?new ByteArrayInputStream(files.get(name)):null);}

    @Test public void manifestAllowsOnlyNineFixedFilesAndValidatesHashAndSize() throws Exception {
        Map<String,byte[]> files=fixtures();SupportManualAssets.Bundle result=load(files);assertEquals(9,result.bytes.size());assertEquals(9,result.metadata.size());
        assertEquals(2,n(result.metadata.get("3:PNG:2"),"page"));assertFalse(result.metadata.get("1:PDF").containsKey("page"));
        for(Map<String,Object> descriptor:result.metadata.values()){assertFalse(descriptor.containsKey("base64"));assertFalse(descriptor.containsKey("path"));assertFalse(descriptor.containsKey("file"));}
        byte[] original=files.get("customer-manual.pdf");files.put("customer-manual.pdf","%PDF-1.7\nchanged\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));assertEquals("MANUAL_ASSET_INVALID",assertThrows(SimException.class,()->load(files)).code);
        files.put("customer-manual.pdf",original);files.remove("platform-manual-page-2.png");assertEquals(503,assertThrows(SimException.class,()->load(files)).status);
    }
    @Test public void manifestCannotRedirectToPathsOrDuplicateAssets() throws Exception {
        Map<String,byte[]> files=fixtures();String manifest=new String(files.get("manifest.json"),StandardCharsets.UTF_8);
        files.put("manifest.json",manifest.replace("customer-manual.pdf","../customer-manual.pdf").getBytes(StandardCharsets.UTF_8));
        List<String> opened=new ArrayList<>();assertThrows(SimException.class,()->SupportManualAssets.load(name->{opened.add(name);return files.containsKey(name)?new ByteArrayInputStream(files.get(name)):null;}));assertEquals(Arrays.asList("manifest.json"),opened);
        files.put("manifest.json",manifest.replace("\"page\":2","\"page\":1").getBytes(StandardCharsets.UTF_8));assertThrows(SimException.class,()->load(files));
        files.put("manifest.json",manifest.replaceFirst("\\{","{\"url\":\"https://example.invalid\",").getBytes(StandardCharsets.UTF_8));assertThrows(SimException.class,()->load(files));
        files.put("manifest.json",manifest.replace("\"version\":1","\"version\":1,\"version\":1").getBytes(StandardCharsets.UTF_8));assertThrows(SimException.class,()->load(files));
        assertNull(SupportManualAssets.load(name->null));
    }
    @Test public void requestIsAuthenticatedAndCannotSelectAPathOrUnsupportedPage() {
        SimContext c=actor("101");assertEquals(401,assertThrows(SimException.class,()->asset(actor(null),params("1","PDF",null))).status);
        for(Map<String,String> p:Arrays.asList(params("4","PDF",null),params("../1","PDF",null),params("1","ZIP",null),params("1","PDF","1"),params("1","PNG",null),params("1","PNG","3"),params("1","PNG","01")))assertEquals(422,assertThrows(SimException.class,()->asset(c,p)).status);
        Map<String,String> path=params("1","PDF",null);path.put("path","/etc/passwd");assertEquals(422,assertThrows(SimException.class,()->asset(c,path)).status);
    }
    @Test public void bundledManualsExposePdfAndTwoRealPageImagesWithoutMutatingState() throws Exception {
        SimContext c=actor("101");String before=JSON.writeValueAsString(c.state);
        for(String id:Arrays.asList("1","2","3")) {
            List<Map<String,Object>> metadata=SupportManualAssets.describe(id);assertEquals(3,metadata.size());
            for(Map<String,Object> item:metadata) {
                String format=s(item,"format");Map<String,Object> downloaded=asset(c,params(id,format,"PNG".equals(format)?s(item,"page"):null));byte[] bytes=Base64.getDecoder().decode(s(downloaded,"base64"));
                assertTrue(bytes.length>1000);assertEquals(n(item,"size"),bytes.length);assertEquals(s(item,"sha256"),hash(bytes));assertEquals("BUILTIN_MANUAL_ASSET",s(downloaded,"source"));
            }
            metadata.get(0).put("name","forged");assertFalse("forged".equals(s(SupportManualAssets.describe(id).get(0),"name")));
        }
        assertEquals(before,JSON.writeValueAsString(c.state));assertTrue(SupportManualAssets.describe("9999").isEmpty());
    }
}
