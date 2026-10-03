package cn.iocoder.yudao.server.simulation;
import java.util.Map;
public interface SimModule {
    boolean supports(String operationId);
    Object execute(String operationId, SimContext context, Map<String,Object> body, Map<String,String> params);
}
