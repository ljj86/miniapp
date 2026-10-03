package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Reads the frozen contract. Rejects undeclared properties before entering domain logic. */
public final class ContractCatalog {
    public static final class Operation {
        public String id,method,path,requestSchema,responseSchema,resultSchema;
        public boolean paged,idempotency,anonymous,callback;
        public List<String> roles=new ArrayList<>();
        public JsonNode definition;
        public Pattern regex;
        public List<String> pathNames=new ArrayList<>();
    }
    public final JsonNode spec;
    public final List<Operation> operations=new ArrayList<>();
    public final ObjectMapper json=new ObjectMapper();
    public ContractCatalog(){
        try(InputStream in=getClass().getResourceAsStream("/simulation/openapi.json")){
            if(in==null)throw new IllegalStateException("Frozen API contract missing");spec=json.readTree(in);
        }catch(IOException e){throw new IllegalStateException(e);}
        Iterator<Map.Entry<String,JsonNode>> paths=spec.path("paths").fields();
        while(paths.hasNext()){
            Map.Entry<String,JsonNode> path=paths.next();Iterator<Map.Entry<String,JsonNode>> methods=path.getValue().fields();
            while(methods.hasNext()){
                Map.Entry<String,JsonNode> method=methods.next();if(!method.getValue().has("operationId"))continue;
                JsonNode d=method.getValue();Operation o=new Operation();o.id=d.path("operationId").asText();o.method=method.getKey().toUpperCase(Locale.ROOT);o.path="/api/v1"+path.getKey();o.definition=d;
                o.requestSchema=d.path("x-request-schema").isNull()?null:d.path("x-request-schema").asText(null);o.resultSchema=d.path("x-result-schema").asText(null);o.paged=d.path("x-paged").asBoolean();o.idempotency=d.path("x-idempotency-required").asBoolean();
                for(JsonNode role:d.path("x-required-roles"))o.roles.add(role.asText());o.anonymous=o.roles.contains("ANON");o.callback=o.roles.contains("MOCK_HMAC");
                String ref=d.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref").asText();o.responseSchema=ref.isEmpty()?null:ref.substring(ref.lastIndexOf('/')+1);
                Matcher m=Pattern.compile("\\{([^}]+)\\}").matcher(o.path);StringBuffer pattern=new StringBuffer();while(m.find()){o.pathNames.add(m.group(1));m.appendReplacement(pattern,"([^/]+)");}m.appendTail(pattern);o.regex=Pattern.compile("^"+pattern+"$");operations.add(o);
            }
        }
        operations.sort((a,b)->Integer.compare(a.pathNames.size(),b.pathNames.size()));
    }
    public Operation match(String method,String path,Map<String,String> params){
        for(Operation o:operations)if(o.method.equals(method)){Matcher m=o.regex.matcher(path);if(m.matches()){for(int i=0;i<o.pathNames.size();i++)params.put(o.pathNames.get(i),m.group(i+1));return o;}}
        throw new SimException(404,"RESOURCE_NOT_FOUND","此模拟接口不存在");
    }
    public void request(Operation op,Map<String,Object> body,Map<String,String> params){
        if(op.requestSchema!=null && !"FileUpload".equals(op.requestSchema))validate(op.requestSchema,body);
        for(JsonNode p:op.definition.path("parameters")){
            String name=p.path("name").asText();if("header".equals(p.path("in").asText()))continue;String value=params.get(name);
            if(value==null){if(p.path("required").asBoolean())fail("缺少参数"+name);continue;}
            JsonNode schema=p.path("schema");Object typed=value;if("integer".equals(schema.path("type").asText()))try{typed=Long.valueOf(value);}catch(NumberFormatException e){fail(name+"必须为整数");}
            validateNode(schema,json.valueToTree(typed),name,0);
        }
    }
    public void validate(String schema,Object value){validateNode(spec.path("components").path("schemas").path(schema),json.valueToTree(value),schema,0);}
    private void validateNode(JsonNode schema,JsonNode value,String at,int depth){
        if(depth>48)fail("结构过深");if(schema.isMissingNode())throw new IllegalStateException("Unknown schema "+at);
        if(schema.has("$ref")){String r=schema.path("$ref").asText();if(!r.startsWith("#/components/schemas/"))fail("不支持外部引用");validateNode(spec.path("components").path("schemas").path(r.substring(r.lastIndexOf('/')+1)),value,at,depth+1);return;}
        if(schema.has("allOf"))for(JsonNode part:schema.path("allOf"))validateNode(part,value,at,depth+1);
        if(schema.has("oneOf")){int valid=0;for(JsonNode part:schema.path("oneOf"))try{validateNode(part,value,at,depth+1);valid++;}catch(SimException ignored){}if(valid!=1)fail(at+"必须符合一种允许的格式");return;}
        if(schema.has("const") && !schema.path("const").equals(value))fail(at+"不是允许的固定值");
        if(schema.has("enum")){boolean found=false;for(JsonNode e:schema.path("enum"))if(e.equals(value)){found=true;break;}if(!found)fail(at+"不在允许值中");}
        String type=schema.path("type").asText("");
        if(schema.path("type").isArray()){boolean allowsNull=false;for(JsonNode t:schema.path("type")){if("null".equals(t.asText()))allowsNull=true;else type=t.asText();}if(value.isNull()&&allowsNull)return;}
        if("object".equals(type)){
            if(!value.isObject())fail(at+"必须为对象");for(JsonNode key:schema.path("required"))if(!value.has(key.asText()))fail(at+"缺少"+key.asText());
            Iterator<Map.Entry<String,JsonNode>> fields=value.fields();while(fields.hasNext()){Map.Entry<String,JsonNode> f=fields.next();JsonNode field=schema.path("properties").path(f.getKey());if(field.isMissingNode()){if(schema.has("additionalProperties")&&!schema.path("additionalProperties").asBoolean(true))fail(at+"包含未允许的字段"+f.getKey());}else validateNode(field,f.getValue(),at+"."+f.getKey(),depth+1);}
        }else if("array".equals(type)){
            if(!value.isArray())fail(at+"必须为数组");if(schema.has("minItems")&&value.size()<schema.path("minItems").asInt())fail(at+"数组过短");if(schema.has("maxItems")&&value.size()>schema.path("maxItems").asInt())fail(at+"数组过长");
            Set<JsonNode> seen=new HashSet<>();for(JsonNode item:value){if(schema.path("uniqueItems").asBoolean()&&!seen.add(item))fail(at+"不能包含重复值");validateNode(schema.path("items"),item,at+"[]",depth+1);}
        }else if("string".equals(type)){
            if(!value.isTextual())fail(at+"必须为字符串");String s=value.asText();int length=s.codePointCount(0,s.length());if(schema.has("minLength")&&length<schema.path("minLength").asInt())fail(at+"过短");if(schema.has("maxLength")&&length>schema.path("maxLength").asInt())fail(at+"过长");if(schema.has("pattern")&&!Pattern.compile(schema.path("pattern").asText()).matcher(s).find())fail(at+"格式不正确");
            try{if("date-time".equals(schema.path("format").asText()))OffsetDateTime.parse(s);if("date".equals(schema.path("format").asText()))LocalDate.parse(s);}catch(Exception e){fail(at+"日期格式不正确");}
        }else if("integer".equals(type)||"number".equals(type)){
            if(!value.isNumber()||("integer".equals(type)&&!value.isIntegralNumber()))fail(at+"必须为整数");BigDecimal n=value.decimalValue();if(schema.has("minimum")&&n.compareTo(schema.path("minimum").decimalValue())<0)fail(at+"小于最小值");if(schema.has("maximum")&&n.compareTo(schema.path("maximum").decimalValue())>0)fail(at+"超过最大值");
        }else if("boolean".equals(type)&&!value.isBoolean())fail(at+"必须为布尔值");
    }
    private static void fail(String message){throw new SimException(422,"VALIDATION_FAILED",message);}
}
