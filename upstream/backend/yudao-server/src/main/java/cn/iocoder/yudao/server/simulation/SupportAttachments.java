package cn.iocoder.yudao.server.simulation;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/**
 * Bounded simulation evidence, stored in the existing transaction JSON aggregate.
 * These are unscanned bytes, never file-system paths, executable uploads or public URLs.
 * Signature checks are not malware detection. Production needs object storage and scanning.
 */
public final class SupportAttachments {
    public static final int MAX_FILE_BYTES = 512 * 1024;
    public static final int MAX_BASE64_LENGTH = ((MAX_FILE_BYTES + 2) / 3) * 4;
    public static final int MAX_BOUND_BYTES = 1024 * 1024;
    private static final long ACTOR_QUOTA = 8L * 1024 * 1024;
    private static final long AGGREGATE_QUOTA = 32L * 1024 * 1024;
    private static final int ACTOR_COUNT_QUOTA = 128;
    private static final int AGGREGATE_COUNT_QUOTA = 1024;
    private static final String KIND = "supportAttachments";
    private static final String SECURITY = "UNSCANNED_SIMULATION";
    private static final Set<String> MIMES = new HashSet<>(Arrays.asList(
            "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf", "text/plain", "video/mp4"));
    private SupportAttachments() {}

    public static Object execute(String op, SimContext c, Map<String,Object> body, Map<String,String> params) {
        c.requireRoles("USER");
        switch (op) {
            case "attachments.upload": return upload(c, body);
            case "attachments.detail": return metadata(c, readable(c, params.get("id")));
            case "attachments.download":
                Map<String,Object> row = readable(c, params.get("id"));
                Map<String,Object> result = c.project(row, "id", "name", "mime", "size", "sha256", "base64");
                result.put("securityStatus", SECURITY);
                result.put("environment", "SIMULATION");
                return result;
            default: throw new SimException(404, "RESOURCE_NOT_FOUND", "附件操作不存在");
        }
    }

    private static Map<String,Object> upload(SimContext c, Map<String,Object> body) {
        keys(c, body, "name", "mime", "base64");
        String name = string(c, body, "name", 128), mime = string(c, body, "mime", 40);
        safeName(c, name);
        c.check(MIMES.contains(mime), 422, "VALIDATION_FAILED", "不支持此附件类型");
        String encoded = string(c, body, "base64", MAX_BASE64_LENGTH);
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw invalid("附件必须是标准Base64编码"); }
        c.check(bytes.length > 0 && bytes.length <= MAX_FILE_BYTES, 422, "ATTACHMENT_TOO_LARGE", "每个附件必须为1至512KiB");
        c.check(Base64.getEncoder().encodeToString(bytes).equals(encoded), 422, "VALIDATION_FAILED", "附件必须是规范的标准Base64编码");
        c.check(matches(mime, bytes), 422, "ATTACHMENT_TYPE_MISMATCH", "附件内容与声明类型不符或文件已损坏");
        long actorBytes = 0, totalBytes = 0;
        int actorCount = 0, totalCount = 0;
        for (Map<String,Object> row : rows(c)) {
            totalBytes = Math.addExact(totalBytes, n(row, "size")); totalCount++;
            if (c.actorId().equals(s(row, "uploaderId"))) { actorBytes = Math.addExact(actorBytes, n(row, "size")); actorCount++; }
        }
        c.check(actorBytes + bytes.length <= ACTOR_QUOTA && totalBytes + bytes.length <= AGGREGATE_QUOTA &&
                actorCount < ACTOR_COUNT_QUOTA && totalCount < AGGREGATE_COUNT_QUOTA,
                409, "ATTACHMENT_QUOTA_EXCEEDED", "模拟附件存储或数量额度已用完");
        Map<String,Object> row = c.create(KIND, map("uploaderId", c.actorId(), "name", name, "mime", mime,
                "size", (long)bytes.length, "sha256", digest(bytes), "base64", encoded,
                "securityStatus", SECURITY, "environment", "SIMULATION"));
        c.audit("SUPPORT_ATTACHMENT_UPLOADED", KIND, s(row, "id"), c.project(row, "name", "mime", "size", "sha256"));
        return metadata(c, row);
    }

    /** Validate the entire set before binding any bytes. Caller must authorize the target command. */
    public static List<Map<String,Object>> attach(SimContext c, List<String> ids, String resourceType, String resourceId) {
        c.requireRoles("USER");
        c.check("session".equals(resourceType) || "ticket".equals(resourceType), 422, "VALIDATION_FAILED", "附件目标类型无效");
        validId(c, resourceId);
        c.check(SupportModule.canReadResource(c, resourceType, resourceId), 404, "RESOURCE_NOT_FOUND", "资源不存在或不可访问");
        c.check(ids != null && ids.size() <= 5 && new HashSet<>(ids).size() == ids.size(),
                422, "VALIDATION_FAILED", "每次最多关联5个不同附件");
        List<Map<String,Object>> selected = new ArrayList<>();
        long total = 0;
        for (String id : ids) {
            Map<String,Object> row = get(c, id);
            c.check(c.actorId().equals(s(row, "uploaderId")), 404, "RESOURCE_NOT_FOUND", "附件不存在或不可访问");
            c.check(row.get("resourceType") == null || (resourceType.equals(s(row, "resourceType")) && resourceId.equals(s(row, "resourceId"))),
                    409, "ATTACHMENT_ALREADY_BOUND", "附件已关联其他资源");
            total += n(row, "size");
            selected.add(row);
        }
        c.check(total <= MAX_BOUND_BYTES, 422, "ATTACHMENTS_TOO_LARGE", "每次关联附件合计不能超过1MiB");
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> row : selected) {
            if (row.get("resourceType") == null) {
                row.put("resourceType", resourceType); row.put("resourceId", resourceId);
                row.put("boundAt", c.now().toString()); c.bump(row);
                c.audit("SUPPORT_ATTACHMENT_BOUND", KIND, s(row, "id"), map("resourceType", resourceType, "resourceId", resourceId));
            }
            result.add(metadata(c, row));
        }
        return result;
    }

    /** Metadata is subject to the same current target scope as downloads, including archive policy. */
    public static List<Map<String,Object>> describe(SimContext c, List<String> ids) {
        c.requireRoles("USER");
        c.check(ids != null && ids.size() <= 5 && new HashSet<>(ids).size() == ids.size(), 422, "VALIDATION_FAILED", "附件标识无效");
        List<Map<String,Object>> result = new ArrayList<>();
        for (String id : ids) result.add(metadata(c, readable(c, id)));
        return result;
    }

    private static Map<String,Object> readable(SimContext c, String id) {
        Map<String,Object> row = get(c, id);
        boolean allowed = row.get("resourceType") == null ? c.actorId().equals(s(row, "uploaderId")) :
                SupportModule.canReadResource(c, s(row, "resourceType"), s(row, "resourceId"));
        c.check(allowed, 404, "RESOURCE_NOT_FOUND", "附件不存在或不可访问");
        return row;
    }
    private static Map<String,Object> get(SimContext c, String id) {
        validId(c, id);
        Map<String,Map<String,Object>> table = c.state.records.get(KIND);
        Map<String,Object> row = table == null ? null : table.get(id);
        c.check(row != null, 404, "RESOURCE_NOT_FOUND", "附件不存在或不可访问");
        return row;
    }
    private static void validId(SimContext c, String id) {
        c.check(id != null && id.matches("[1-9][0-9]{0,19}"), 422, "VALIDATION_FAILED", "附件或目标标识无效");
    }
    private static Collection<Map<String,Object>> rows(SimContext c) {
        Map<String,Map<String,Object>> table = c.state.records.get(KIND);
        return table == null ? Collections.<Map<String,Object>>emptyList() : table.values();
    }
    private static Map<String,Object> metadata(SimContext c, Map<String,Object> row) {
        return c.project(row, "id", "name", "mime", "size", "sha256", "securityStatus", "resourceType", "resourceId", "boundAt", "createdAt", "updatedAt", "version", "environment");
    }
    private static void safeName(SimContext c, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean valid = name.equals(name.trim()) && !name.startsWith(".") && !name.endsWith(".") &&
                !name.contains("..") && !name.matches(".*[\\\\/:<>\"|?*%#&].*") &&
                !lower.matches(".*\\.(html?|svg|svgz|js|mjs|exe|sh|bat|cmd|com|jar|php)$");
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i)) || Character.getType(name.charAt(i)) == Character.FORMAT) valid = false;
        c.check(valid, 422, "VALIDATION_FAILED", "附件名称必须是安全文件名，不能包含路径或脚本后缀");
    }
    private static String string(SimContext c, Map<String,Object> b, String field, int max) {
        Object v = b.get(field);
        c.check(v instanceof String && !((String)v).trim().isEmpty() && ((String)v).length() <= max, 422, "VALIDATION_FAILED", field + "无效或超过限制");
        return (String)v;
    }
    private static void keys(SimContext c, Map<String,Object> body, String... keys) {
        c.check(new HashSet<>(Arrays.asList(keys)).containsAll(body.keySet()), 422, "VALIDATION_FAILED", "附件包含未允许字段");
    }
    private static SimException invalid(String message) { return new SimException(422, "VALIDATION_FAILED", message); }
    private static String digest(byte[] bytes) {
        try { StringBuilder result = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", b)); return result.toString(); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean prefix(byte[] b, int... wanted) { if (b.length < wanted.length) return false; for (int i=0;i<wanted.length;i++) if ((b[i]&255)!=wanted[i]) return false; return true; }
    private static String ascii(byte[] b, int offset, int length) { return new String(b,offset,length,StandardCharsets.US_ASCII); }
    private static long u32(byte[] b,int p) { return ((long)(b[p]&255)<<24)|((long)(b[p+1]&255)<<16)|((long)(b[p+2]&255)<<8)|(b[p+3]&255); }
    private static long le32(byte[] b,int p) { return ((long)(b[p+3]&255)<<24)|((long)(b[p+2]&255)<<16)|((long)(b[p+1]&255)<<8)|(b[p]&255); }
    private static boolean matches(String mime, byte[] b) {
        switch (mime) {
            case "image/png": return png(b);
            case "image/jpeg": return jpeg(b);
            case "image/gif": return gif(b);
            case "image/webp": return webp(b);
            case "application/pdf":
                if (b.length<16 || !ascii(b,0,8).matches("%PDF-(1\\.[0-7]|2\\.0)") || (b[8]!=10 && b[8]!=13)) return false;
                return ascii(b,Math.max(0,b.length-1024),Math.min(1024,b.length)).trim().endsWith("%%EOF");
            case "text/plain": return text(b);
            case "video/mp4": return mp4(b);
            default: return false;
        }
    }
    private static boolean text(byte[] b) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
            for (int i=0;i<text.length();i++) if (Character.isISOControl(text.charAt(i)) && "\r\n\t".indexOf(text.charAt(i))<0) return false;
            String lower=text.toLowerCase(Locale.ROOT);
            return !lower.matches("(?s).*<(?:!doctype\\s+html|html\\b|svg\\b|script\\b).*");
        } catch (CharacterCodingException e) { return false; }
    }
    private static boolean jpeg(byte[] b) {
        if (!prefix(b,255,216,255)) return false;
        int p=2; boolean frame=false, scan=false;
        while (p<b.length) {
            if ((b[p++]&255)!=255) return false;
            while(p<b.length && (b[p]&255)==255)p++;
            if(p>=b.length)return false;
            int marker=b[p++]&255;
            if(marker==217)return frame && scan && p==b.length;
            if(marker==0 || marker==216 || (marker>=208&&marker<=215))return false;
            if(p+2>b.length)return false;
            int size=((b[p]&255)<<8)|(b[p+1]&255);
            if(size<2 || size>b.length-p)return false;
            if(marker>=192&&marker<=207 && marker!=196&&marker!=200&&marker!=204) {
                if(size<11 || (b[p+7]&255)==0 || size!=8+3*(b[p+7]&255) || ((b[p+3]&255)|(b[p+4]&255))==0 || ((b[p+5]&255)|(b[p+6]&255))==0)return false;
                frame=true;
            }
            p+=size;
            if(marker==218) {
                if(!frame)return false;
                scan=true;
                while(p<b.length) {
                    if((b[p]&255)!=255){p++;continue;}
                    if(p+1>=b.length)return false;
                    int next=b[p+1]&255;
                    if(next==0 || (next>=208&&next<=215)){p+=2;continue;}
                    break;
                }
            }
        }
        return false;
    }
    private static boolean gif(byte[] b) {
        if(b.length<14 || !(ascii(b,0,6).equals("GIF87a")||ascii(b,0,6).equals("GIF89a")) || ((b[6]&255)|(b[7]&255))==0 || ((b[8]&255)|(b[9]&255))==0)return false;
        int p=13; if((b[10]&128)!=0)p+=3*(1<<((b[10]&7)+1));
        boolean image=false;
        while(p<b.length) {
            int marker=b[p++]&255;
            if(marker==59)return image && p==b.length;
            if(marker==33) { if(p>=b.length)return false; p++; }
            else if(marker==44) {
                if(p+9>b.length || ((b[p+4]&255)|(b[p+5]&255))==0 || ((b[p+6]&255)|(b[p+7]&255))==0)return false;
                int packed=b[p+8]&255; p+=9;
                if((packed&128)!=0)p+=3*(1<<((packed&7)+1));
                if(p>=b.length || (b[p]&255)<2 || (b[p]&255)>8)return false;
                p++;image=true;
            } else return false;
            boolean end=false;
            while(p<b.length) { int size=b[p++]&255; if(size==0){end=true;break;} if(size>b.length-p)return false; p+=size; }
            if(!end)return false;
        }
        return false;
    }
    private static boolean webp(byte[] b) {
        if(b.length<20 || !ascii(b,0,4).equals("RIFF") || !ascii(b,8,4).equals("WEBP") || le32(b,4)!=b.length-8 || !Arrays.asList("VP8 ","VP8L","VP8X").contains(ascii(b,12,4)))return false;
        return webpChunks(b,12,b.length,false);
    }
    private static boolean webpChunks(byte[] b,int p,int end,boolean frame) {
        boolean image=false;
        while(p+8<=end) {
            long count=le32(b,p+4); if(count>end-p-8)return false;
            int size=(int)count,data=p+8;String kind=ascii(b,p,4);
            if("VP8L".equals(kind)) { if(size<5 || (b[data]&255)!=47)return false; image=true; }
            else if("VP8 ".equals(kind)) { if(size<10 || (b[data+3]&255)!=157 || (b[data+4]&255)!=1 || (b[data+5]&255)!=42)return false;image=true; }
            else if("VP8X".equals(kind)) { if(frame||size!=10)return false; }
            else if("ANMF".equals(kind)) { if(frame||size<24||!webpChunks(b,data+16,data+size,true))return false;image=true; }
            p=data+size+(size%2);
        }
        return image && p==end;
    }
    private static boolean png(byte[] b) {
        if (!prefix(b,137,80,78,71,13,10,26,10)) return false;
        int offset=8; boolean header=false, data=false;
        while (offset+12<=b.length) {
            long length=u32(b,offset); if (length>b.length-offset-12) return false;
            int size=(int)length; String type=ascii(b,offset+4,4);
            if (!type.matches("[A-Za-z]{4}")) return false;
            CRC32 crc=new CRC32(); crc.update(b,offset+4,size+4);
            if (crc.getValue()!=u32(b,offset+8+size)) return false;
            if (!header) { if (!"IHDR".equals(type)||size!=13||u32(b,offset+8)==0||u32(b,offset+12)==0) return false; header=true; }
            else if ("IHDR".equals(type)) return false;
            if ("IDAT".equals(type)) data=true;
            offset+=12+size;
            if ("IEND".equals(type)) return size==0 && data && offset==b.length;
        }
        return false;
    }
    private static boolean mp4(byte[] b) {
        if (b.length<24 || !ascii(b,4,4).equals("ftyp")) return false;
        long box=u32(b,0); if (box<16||box>b.length||box%4!=0) return false;
        Set<String> brands=new HashSet<>(Arrays.asList("isom","iso2","mp41","mp42","avc1","M4V ","dash"));
        boolean recognized=brands.contains(ascii(b,8,4));
        for (int p=16;p<box;p+=4) recognized|=brands.contains(ascii(b,p,4));
        if (!recognized) return false;
        int p=(int)box; boolean media=false;
        while (p+8<=b.length) { long size=u32(b,p); if (size==0) size=b.length-p; if(size<8||size>b.length-p) return false; String type=ascii(b,p+4,4); if ("mdat".equals(type)||"moov".equals(type)) media=true; p+=(int)size; }
        return media && p==b.length;
    }
}
