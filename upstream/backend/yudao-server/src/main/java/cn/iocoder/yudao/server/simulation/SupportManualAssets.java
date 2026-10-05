package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/**
 * Built-in source manuals only. The request selects a fixed ID and format, never a path.
 * Bundled size/hash metadata is verified before bytes or descriptors can be returned.
 * This is distinct from user-uploaded, unscanned support attachments.
 */
public final class SupportManualAssets {
    private static final String ROOT = "/simulation/manual-assets/";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Map<String,String> BASENAMES = new LinkedHashMap<>();
    private static volatile Bundle cached;
    static {
        BASENAMES.put("1", "customer-manual"); BASENAMES.put("2", "merchant-manual"); BASENAMES.put("3", "platform-manual");
    }
    private SupportManualAssets() {}

    public static Object execute(String op, SimContext c, Map<String,Object> body, Map<String,String> params) {
        c.requireRoles("USER");
        c.check("manuals.asset".equals(op), 404, "RESOURCE_NOT_FOUND", "操作手册附件操作不存在");
        c.check(body.isEmpty(), 422, "VALIDATION_FAILED", "手册下载不接受正文");
        for (String key : params.keySet()) c.check(key.startsWith("_") || "id".equals(key) || "format".equals(key) || "page".equals(key), 422, "VALIDATION_FAILED", "手册下载包含未允许参数");
        String id = params.get("id"), format = params.get("format"), page=params.get("page");
        c.check(BASENAMES.containsKey(id) && ("PDF".equals(format) && page==null || "PNG".equals(format) && Arrays.asList("1","2").contains(page)), 422, "VALIDATION_FAILED", "手册编号、格式或页码无效");
        Bundle bundle = bundle();
        c.check(bundle != null, 503, "MANUAL_ASSET_UNAVAILABLE", "当前构建尚未包含手册附件，请使用纯文本手册");
        String key = id + ":" + format + ("PNG".equals(format)?":"+page:"");
        Map<String,Object> result = new LinkedHashMap<>(bundle.metadata.get(key));
        result.put("base64", Base64.getEncoder().encodeToString(bundle.bytes.get(key)));
        return result;
    }

    /** No classpath paths are exposed; missing assets are never advertised as available. */
    public static List<Map<String,Object>> describe(String manualId) {
        if (!BASENAMES.containsKey(manualId)) return Collections.emptyList();
        Bundle bundle = bundle();
        if (bundle == null) return Collections.emptyList();
        List<Map<String,Object>> result = new ArrayList<>();
        for (String suffix : Arrays.asList("PDF", "PNG:1", "PNG:2")) result.add(new LinkedHashMap<>(bundle.metadata.get(manualId + ":" + suffix)));
        return result;
    }

    private static Bundle bundle() {
        Bundle result = cached;
        if (result != null) return result;
        synchronized (SupportManualAssets.class) {
            if (cached == null) cached = load(name -> SupportManualAssets.class.getResourceAsStream(ROOT + name));
            return cached;
        }
    }
    interface Source { InputStream open(String fixedName) throws IOException; }
    static final class Bundle {
        final Map<String,Map<String,Object>> metadata = new LinkedHashMap<>();
        final Map<String,byte[]> bytes = new LinkedHashMap<>();
    }

    /** Package-local source injection allows verification without accepting any request paths. */
    static Bundle load(Source source) {
        try {
            byte[] manifest = read(source.open("manifest.json"), 64 * 1024);
            if (manifest == null) return null;
            JsonNode root = JSON.readTree(manifest);
            exact(root, "version", "manuals");
            require(root.path("version").isIntegralNumber() && root.path("version").canConvertToInt() && root.path("version").asInt() == 1);
            JsonNode manuals = root.path("manuals"); require(manuals.isArray() && manuals.size() == 3);
            Set<String> seen = new HashSet<>(); Bundle bundle = new Bundle();
            for (JsonNode manual : manuals) {
                exact(manual, "id", "title", "assets");
                require(manual.path("id").isTextual() && BASENAMES.containsKey(manual.path("id").asText()) && seen.add(manual.path("id").asText()));
                String id = manual.path("id").asText();
                require(manual.path("title").isTextual() && !manual.path("title").asText().trim().isEmpty() && manual.path("title").asText().length() <= 160);
                JsonNode assets = manual.path("assets"); require(assets.isArray() && assets.size() == 3);
                for (JsonNode asset : assets) {
                    require(asset.path("format").isTextual()); String format = asset.path("format").asText();
                    require("PDF".equals(format) || "PNG".equals(format));
                    int page=0;
                    if("PDF".equals(format)) exact(asset, "format", "file", "mime", "size", "sha256");
                    else {
                        exact(asset, "format", "page", "file", "mime", "size", "sha256");
                        require(asset.path("page").isIntegralNumber() && asset.path("page").canConvertToInt() && (asset.path("page").asInt()==1 || asset.path("page").asInt()==2));
                        page=asset.path("page").asInt();
                    }
                    String key = id + ":" + format + (page>0?":"+page:""), file = BASENAMES.get(id) + ("PDF".equals(format) ? ".pdf" : "-page-"+page+".png");
                    String mime = "PDF".equals(format) ? "application/pdf" : "image/png";
                    require(!bundle.metadata.containsKey(key) && asset.path("file").isTextual() && file.equals(asset.path("file").asText()));
                    require(asset.path("mime").isTextual() && mime.equals(asset.path("mime").asText()));
                    require(asset.path("size").isIntegralNumber() && asset.path("size").canConvertToLong() && asset.path("size").asLong() > 0 && asset.path("size").asLong() <= MAX_BYTES);
                    require(asset.path("sha256").isTextual() && asset.path("sha256").asText().matches("[0-9a-f]{64}"));
                    // Only the compile-time mapping reaches Source.open; asset.file is comparison-only.
                    byte[] bytes = read(source.open(file), MAX_BYTES);
                    require(bytes != null && bytes.length == asset.path("size").asLong() && hash(bytes).equals(asset.path("sha256").asText()));
                    require(signature(format, bytes));
                    bundle.bytes.put(key, bytes);
                    Map<String,Object> metadata=map("manualId", id, "format", format, "name", file, "mime", mime,
                            "size", (long)bytes.length, "sha256", asset.path("sha256").asText(),
                            "source", "BUILTIN_MANUAL_ASSET", "environment", "SIMULATION");
                    if(page>0)metadata.put("page",page);
                    bundle.metadata.put(key,metadata);
                }
            }
            require(bundle.metadata.size() == 9);
            return bundle;
        } catch (SimException e) { throw e; }
        catch (IOException | RuntimeException e) { throw new SimException(503, "MANUAL_ASSET_INVALID", "构建内置手册附件校验失败"); }
    }
    private static byte[] read(InputStream input, int max) throws IOException {
        if (input == null) return null;
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int count, size = 0;
            while ((count = in.read(block)) != -1) { size += count; require(size <= max); out.write(block,0,count); }
            return out.toByteArray();
        }
    }
    private static void exact(JsonNode node, String... allowed) {
        require(node != null && node.isObject() && node.size() == allowed.length);
        Set<String> keys = new HashSet<>(Arrays.asList(allowed));
        node.fieldNames().forEachRemaining(key -> require(keys.contains(key)));
        for (String key : allowed) require(node.has(key));
    }
    private static void require(boolean okay) { if (!okay) throw new SimException(503,"MANUAL_ASSET_INVALID","构建内置手册附件校验失败"); }
    private static boolean signature(String format, byte[] bytes) {
        if ("PDF".equals(format)) return bytes.length >= 16 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-") &&
                new String(bytes,Math.max(0,bytes.length-1024),Math.min(1024,bytes.length),StandardCharsets.US_ASCII).trim().endsWith("%%EOF");
        return bytes.length >= 20 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
    }
    private static String hash(byte[] bytes) {
        try { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x",b)); return out.toString(); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
