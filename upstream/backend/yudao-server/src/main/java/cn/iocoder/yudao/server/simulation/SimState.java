package cn.iocoder.yudao.server.simulation;
import java.util.*;

/** One isolated simulation partition. It is never a production customer store. */
public final class SimState {
    public Map<String,Object> meta = new LinkedHashMap<>();
    public Map<String,LinkedHashMap<String,Map<String,Object>>> records = new LinkedHashMap<>();
    public long sequence = 10000;
    public LinkedHashMap<String,Map<String,Object>> table(String kind) {
        if (!records.containsKey(kind)) records.put(kind, new LinkedHashMap<String,Map<String,Object>>());
        return records.get(kind);
    }
}
