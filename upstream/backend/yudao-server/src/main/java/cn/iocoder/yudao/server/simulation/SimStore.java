package cn.iocoder.yudao.server.simulation;
import java.util.function.Function;
public interface SimStore { <T> T transaction(Function<SimState,T> work); }
