package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.*;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Dedicated contract boundary. Every protected operation is authorized by SimulationEngine. */
public final class SimulationApiFilter extends OncePerRequestFilter {
    private final SimulationEngine engine;private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    public SimulationApiFilter(SimulationEngine engine){this.engine=engine;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String requestId=UUID.randomUUID().toString();response.setHeader("X-Request-Id",requestId);response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        String origin=request.getHeader("Origin");if("http://127.0.0.1:5174".equals(origin)||"http://localhost:5174".equals(origin)){response.setHeader("Access-Control-Allow-Origin",origin);response.setHeader("Vary","Origin");response.setHeader("Access-Control-Allow-Headers","Authorization,Content-Type,Idempotency-Key");response.setHeader("Access-Control-Allow-Methods","GET,POST,PATCH,DELETE,OPTIONS");}
        if("OPTIONS".equals(request.getMethod())){response.setStatus(204);return;}
        try{
            String path=request.getRequestURI().substring(request.getContextPath().length());if(path.contains("%")||path.contains(";")||path.contains(".."))throw new SimException(422,"VALIDATION_FAILED","路径格式无效");
            String auth=request.getHeader("Authorization");if(auth!=null && !auth.startsWith("Bearer "))throw new SimException(401,"AUTH_REQUIRED","需要Bearer会话凭证");String access=auth==null?null:auth.substring(7);
            Map<String,String> headers=new LinkedHashMap<>();Enumeration<String> names=request.getHeaderNames();while(names.hasMoreElements()){String name=names.nextElement();headers.put(name.toLowerCase(Locale.ROOT),request.getHeader(name));}headers.put("authorization",access);
            Map<String,String> params=new LinkedHashMap<>();for(Map.Entry<String,String[]> e:request.getParameterMap().entrySet()){if(e.getKey().startsWith("_")||e.getValue().length!=1)throw new SimException(422,"VALIDATION_FAILED","查询参数无效");params.put(e.getKey(),e.getValue()[0]);}params.put("_remote",request.getRemoteAddr());
            if("GET".equals(request.getMethod())&&"/api/v1/simulation-fixtures".equals(path)){send(response,200,engine.fixtures());return;}
            if("GET".equals(request.getMethod())&&"/api/v1/simulation-workspace".equals(path)){send(response,200,engine.workspace(access));return;}
            if("GET".equals(request.getMethod())&&"/api/v1/admin/simulator/channel-bill".equals(path)){Object csv=engine.supplemental("channelBill",null,map("merchantUid",params.get("merchantUid"),"businessDate",params.get("businessDate")),access);response.setContentType("text/csv;charset=UTF-8");response.getWriter().write(String.valueOf(csv));return;}
            Matcher download=Pattern.compile("^/api/v1/exports/([0-9]+)/content$").matcher(path);
            if("GET".equals(request.getMethod())&&download.matches()){
                byte[] data=engine.download(download.group(1),params.get("token"),access);response.setContentType("text/csv;charset=UTF-8");response.setHeader("Content-Disposition","attachment; filename=simulation-export.csv");response.getOutputStream().write(data);return;
            }
            Map<String,Object> body=new LinkedHashMap<>();byte[] raw=new byte[0];
            if(request.getContentType()!=null&&request.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/form-data")){
                Part part=request.getPart("file");if(part==null)throw new SimException(422,"VALIDATION_FAILED","缺少文件");byte[] bytes=bounded(part.getInputStream(),2*1024*1024);body.put("_fileBytes",bytes);body.put("_filename",part.getSubmittedFileName());body.put("purpose",request.getParameter("purpose"));raw=json.writeValueAsBytes(body);
            }else{
                raw=bounded(request.getInputStream(),1024*1024);if(raw.length>0){
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw));JsonNode parsed=json.readTree(raw);if(!parsed.isObject())throw new SimException(422,"VALIDATION_FAILED","请求正文必须为JSON对象");body=json.convertValue(parsed,Map.class);
                }
            }
            if("POST".equals(request.getMethod())&&"/api/v1/simulation-controls/failures".equals(path)){
                Set<String> allowed=new HashSet<>(Arrays.asList("reference","version","result","reason","eventId"));for(String key:body.keySet())if(!allowed.contains(key))throw new SimException(422,"VALIDATION_FAILED","未知模拟控制字段");
                if(!body.keySet().containsAll(allowed))throw new SimException(422,"VALIDATION_FAILED","模拟故障控制字段不完整");send(response,200,engine.failure(body,access,requestId));return;
            }
            if("POST".equals(request.getMethod())&&"/api/v1/simulation-controls/provider-results".equals(path)){if(!(body.get("result") instanceof Map)||!(body.get("deliverCallback") instanceof Boolean)||body.size()!=2)throw new SimException(422,"VALIDATION_FAILED","需要result对象和deliverCallback布尔值");send(response,200,engine.providerResult((Map<String,Object>)body.get("result"),access,Boolean.TRUE.equals(body.get("deliverCallback"))));return;}
            if("POST".equals(request.getMethod())&&"/api/v1/admin/late-allocations".equals(path)){send(response,200,engine.supplemental("lateRequest",null,body,access));return;}
            Matcher late=Pattern.compile("^/api/v1/admin/late-allocations/([0-9]+)/decision$").matcher(path);if("POST".equals(request.getMethod())&&late.matches()){send(response,200,engine.supplemental("lateReview",late.group(1),body,access));return;}
            send(response,200,engine.request(request.getMethod(),path,body,params,headers,raw,requestId));
        }catch(SimException e){if(e.status==429)response.setHeader("Retry-After","60");send(response,e.status,map("code",e.code,"message",e.getMessage(),"requestId",requestId));}
        catch(com.fasterxml.jackson.core.JsonProcessingException|CharacterCodingException e){send(response,422,map("code","VALIDATION_FAILED","message","请求JSON或UTF-8格式不正确","requestId",requestId));}
        catch(Exception e){logger.error("Simulation request failed; requestId="+requestId+" type="+e.getClass().getSimpleName(),e);send(response,500,map("code","INTERNAL_ERROR","message","模拟服务暂时无法完成请求，请凭请求编号查询","requestId",requestId));}
    }
    private void send(HttpServletResponse response,int status,Object data)throws IOException{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),data);}
    private byte[] bounded(InputStream input,int max)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=input.read(buf))>=0){if(out.size()+n>max)throw new SimException(422,"VALIDATION_FAILED","上传或请求正文过大");out.write(buf,0,n);}return out.toByteArray();}
}
