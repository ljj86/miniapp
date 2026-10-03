package cn.iocoder.yudao.server.simulation;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

/** Recovery regression for document candidate values, never formal business approval. */
class SimulationSeedRecoveryTest {
    @Test void candidateRuleUsesDocumentAmountsWithoutFormalApproval() {
        SimState state=SimulationSeed.create(Instant.parse("2026-10-03T00:00:00Z"));
        Map<String,Object> rule=state.table("rules").get(s(state.meta,"ruleId"));
        assertNotNull(rule);
        assertEquals(10000L,n(rule,"quotaMinor"));
        assertEquals(2000L,n(rule,"transactionMaxMinor"));
        assertEquals(30L,n(rule,"termDays"));
        assertTrue(bool(rule,"fixtureOnly"));
        assertFalse(bool(rule,"formalApproval"));
        assertTrue(bool(state.meta,"fixtureMode"));
        assertEquals("SIMULATION",s(state.meta,"environment"));
    }
}
