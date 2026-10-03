package cn.iocoder.yudao.server.simulation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.iocoder.yudao.server.simulation.SimContext.map;
class AppendOnlyRecoveryTest {
    private SimState state() {
        SimState s=new SimState();
        for(String kind:Arrays.asList("journals","lines","audit","reportSnapshots","identityChanges"))
            s.table(kind).put("1",map("id","1","amount",1200L,"evidence",map("hash","original")));
        return s;
    }
    @Test void deletionOfEvidenceIsRejected() {
        for(String kind:Arrays.asList("journals","lines","audit","reportSnapshots","identityChanges")) {
            SimState before=state(),after=state();after.table(kind).remove("1");
            assertThrows(IllegalStateException.class,()->JdbcSimStore.assertAppendOnly(before,after),kind);
        }
    }
    @Test void modificationIncludingNestedEvidenceIsRejected() {
        SimState before=state(),after=state();after.table("journals").get("1").put("amount",1100L);
        assertThrows(IllegalStateException.class,()->JdbcSimStore.assertAppendOnly(before,after));
        SimState nested=state();nested.table("reportSnapshots").get("1").put("evidence",map("hash","replacement"));
        assertThrows(IllegalStateException.class,()->JdbcSimStore.assertAppendOnly(before,nested));
    }
    @Test void appendingEvidenceAndChangingOrdinaryWorkflowIsAllowed() {
        SimState before=state(),after=state();after.table("journals").put("2",map("id","2","amount",300L));
        after.table("orders").put("a",map("status","CANCELLED"));
        assertDoesNotThrow(()->JdbcSimStore.assertAppendOnly(before,after));
    }
}
