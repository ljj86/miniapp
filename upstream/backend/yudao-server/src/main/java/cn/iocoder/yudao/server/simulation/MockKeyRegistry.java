package cn.iocoder.yudao.server.simulation;
import java.time.Instant;
import java.util.*;
/** Injected test/service keys. Empty by default; public signature examples are never service keys. */
public final class MockKeyRegistry {
    private static final class Key {final byte[] secret;final Instant from,until;boolean revoked;Key(byte[] secret,Instant from,Instant until){this.secret=secret.clone();this.from=from;this.until=until;}}
    private final Map<String,Key> keys=new HashMap<>();
    public synchronized void add(String id,byte[] secret,Instant from,Instant until){if(id==null||id.isEmpty()||secret==null||secret.length<32||from==null||until==null||!until.isAfter(from))throw new IllegalArgumentException("Invalid mock key configuration");if(keys.containsKey(id))throw new IllegalArgumentException("Key id already exists; rotate using a new id");keys.put(id,new Key(secret,from,until));}
    public synchronized void revoke(String id){Key key=keys.get(id);if(key!=null)key.revoked=true;}
    public synchronized byte[] resolve(String id,Instant now){if(keys.isEmpty())throw new SimException(503,"SERVICE_UNAVAILABLE","签名回调验证密钥尚未配置；请使用已授权模拟控制台");Key key=keys.get(id);if(key==null||key.revoked||now.isBefore(key.from)||!now.isBefore(key.until))throw new SimException(401,"AUTH_REQUIRED","回调密钥未知、已撤销或不在验证窗口");return key.secret.clone();}
}
