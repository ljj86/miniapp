package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import static cn.iocoder.yudao.server.simulation.SimContext.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationOutboxTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant start = Instant.parse("2026-10-03T00:00:00Z");

    private SimState seed() { return SimulationSeed.create(start); }
    private SimContext context(SimState state, String actor, long seconds) {
        return new SimContext(state,actor,start.plusSeconds(seconds));
    }
    private Map<String,Object> enqueue(SimState state) {
        return NotificationModule.enqueue(context(state,"101",0),"101","模拟账单已生成",
                "receivables","8001","RECEIVABLE_CREATED_V1",1L);
    }
    private void failures(SimState state, long remaining, String userId) {
        SimContext controller = context(state,"701",0);
        NotificationModule.configureFailure(controller,map("version",
                n(NotificationModule.failureConfiguration(controller),"version"),
                "remainingFailures",remaining,"userId",userId,"reason","验证模拟通知失败补偿"));
    }
    private SimState copy(SimState state) throws Exception {
        return json.readValue(json.writeValueAsBytes(state),SimState.class);
    }

    @Test void enqueueIsAsynchronousAndDedupesEventVersionRecipientAndTemplate() {
        SimState state = seed();
        SimContext c = context(state,"101",0);
        Map<String,Object> first = enqueue(state);
        Map<String,Object> replay = NotificationModule.enqueue(c,"101","另一个标题不会覆盖首个快照",
                "receivables","8001","RECEIVABLE_CREATED_V1",1L);
        assertEquals(s(first,"id"),s(replay,"id"));
        assertEquals("模拟账单已生成",s(replay,"title"));
        assertTrue(state.table("notifications").isEmpty());
        assertTrue(state.table("notificationAttempts").isEmpty());
        NotificationModule.enqueue(c,"101","下一版本","receivables","8001","RECEIVABLE_CREATED_V1",2L);
        NotificationModule.enqueue(c,"102","另一用户","receivables","8001","RECEIVABLE_CREATED_V1",1L);
        NotificationModule.enqueue(c,"101","另一模板","receivables","8001","RECEIVABLE_CHANGED_V1",1L);
        assertEquals(4,state.table("notificationOutbox").size());
        assertEquals(4,NotificationModule.drainDue(context(state,null,0)));
        assertEquals(4,state.table("notifications").size());
        assertEquals(4,state.table("notificationAttempts").size());
        assertEquals(0,NotificationModule.drainDue(context(state,null,86400)));
        assertEquals(4,state.table("notifications").size());
        assertEquals(4,state.table("notificationAttempts").size());
    }

    @Test void failureRetriesAtThirtyAndSixtySecondsThenStopsAtDeadLetter() {
        SimState state = seed();
        failures(state,3,"101");
        Map<String,Object> row = enqueue(state);
        assertEquals(1,NotificationModule.drainDue(context(state,null,0)));
        assertEquals("RETRY_WAIT",s(row,"status"));
        assertEquals(start.plusSeconds(30).toString(),s(row,"nextAttemptAt"));
        assertEquals(0,NotificationModule.drainDue(context(state,null,29)));
        assertEquals(1,NotificationModule.drainDue(context(state,null,30)));
        assertEquals(start.plusSeconds(90).toString(),s(row,"nextAttemptAt"));
        assertEquals(0,NotificationModule.drainDue(context(state,null,89)));
        assertEquals(1,NotificationModule.drainDue(context(state,null,90)));
        assertEquals("DEAD",s(row,"status"));
        assertEquals(3,n(row,"attemptCount"));
        assertNull(row.get("nextAttemptAt"));
        assertEquals(start.plusSeconds(90).toString(),s(row,"deadAt"));
        assertEquals(0,NotificationModule.drainDue(context(state,null,86400)));
        assertTrue(state.table("notifications").isEmpty());
        List<Map<String,Object>> attempts = NotificationModule.visibleAttempts(context(state,"101",90),s(row,"id"));
        assertEquals(3,attempts.size());
        for (int i=0;i<attempts.size();i++) {
            assertEquals(i+1,n(attempts.get(i),"sequence"));
            assertEquals("FAILED",s(attempts.get(i),"status"));
            assertEquals("SIMULATED_DELIVERY_FAILURE",s(attempts.get(i),"errorCode"));
        }
        long audits = state.table("audit").values().stream()
                .filter(a -> "NOTIFICATION_DELIVERY_ATTEMPT".equals(s(a,"action"))).count();
        assertEquals(3,audits);
    }

    @Test void delayedSweepMakesOneAttemptAndRestartRetainsRetryState() throws Exception {
        SimState state = seed();
        failures(state,2,"101");
        Map<String,Object> row = enqueue(state);
        String id = s(row,"id");
        NotificationModule.drainDue(context(state,null,0));
        state = copy(state);
        assertEquals(0,NotificationModule.drainDue(context(state,null,29)));
        assertEquals(1,NotificationModule.drainDue(context(state,null,1000)));
        row = state.table("notificationOutbox").get(id);
        assertEquals(2,n(row,"attemptCount"));
        assertEquals(start.plusSeconds(1060).toString(),s(row,"nextAttemptAt"));
        assertEquals(0,NotificationModule.drainDue(context(state,null,1000)));
        assertEquals(1,NotificationModule.drainDue(context(state,null,1060)));
        assertEquals("DELIVERED",s(row,"status"));
        assertEquals(1,state.table("notifications").size());
        assertEquals(3,state.table("notificationAttempts").size());
    }

    @Test void explicitRetryPreservesHistoryAndStartsAnotherBoundedCycle() throws Exception {
        SimState state = seed();
        failures(state,6,"101");
        Map<String,Object> row = enqueue(state);
        String id = s(row,"id");
        for (long seconds : new long[]{0,30,90}) NotificationModule.drainDue(context(state,null,seconds));
        SimState before = copy(state);
        long deadVersion = n(row,"version");
        SimContext controller = context(state,"701",100);
        assertEquals("VERSION_CONFLICT",assertThrows(SimException.class,
                () -> NotificationModule.retry(controller,id,map("version",deadVersion-1,"reason","重试"))).code);
        assertEquals("VALIDATION_FAILED",assertThrows(SimException.class,
                () -> NotificationModule.retry(controller,id,map("version",deadVersion,"reason","  "))).code);
        Map<String,Object> retried = NotificationModule.retry(controller,id,map("version",deadVersion,
                "reason","确认模拟接收端可用，启动新的有限重试周期"));
        assertEquals("PENDING",s(retried,"status"));
        assertEquals(2,n(retried,"cycle"));
        assertEquals(0,n(retried,"cycleAttemptCount"));
        assertEquals(3,n(retried,"attemptCount"));
        assertEquals(3,state.table("notificationAttempts").size());
        assertEquals(json.writeValueAsString(before.table("notificationAttempts")),
                json.writeValueAsString(state.table("notificationAttempts")));
        List<Map<String,Object>> retryAudits = NotificationModule.visibleRetryAudits(controller,id);
        assertEquals(1,retryAudits.size());
        assertEquals(deadVersion,n(retryAudits.get(0),"fromVersion"));
        assertEquals(deadVersion+1,n(retryAudits.get(0),"toVersion"));
        assertEquals("701",s(retryAudits.get(0),"actorId"));
        assertEquals("STATE_CONFLICT",assertThrows(SimException.class,
                () -> NotificationModule.retry(controller,id,map("version",n(row,"version"),"reason","再次提交"))).code);
        for (long seconds : new long[]{100,130,190}) NotificationModule.drainDue(context(state,null,seconds));
        assertEquals("DEAD",s(row,"status"));
        assertEquals(6,n(row,"attemptCount"));
        assertEquals(3,n(row,"cycleAttemptCount"));
        assertEquals(0,NotificationModule.drainDue(context(state,null,1000)));
        NotificationModule.retry(context(state,"701",1000),id,map("version",n(row,"version"),"reason","模拟故障已解除"));
        NotificationModule.drainDue(context(state,null,1000));
        assertEquals("DELIVERED",s(row,"status"));
        assertEquals(7,n(row,"attemptCount"));
        assertEquals(3,n(row,"cycle"));
        assertEquals(1,n(row,"cycleAttemptCount"));
        assertEquals(1,state.table("notifications").size());
        assertEquals(7,state.table("notificationAttempts").size());
        JdbcSimStore.assertAppendOnly(before,copy(state));
    }

    @Test void attemptsAndRetryAuditsCannotBeDeletedOrModified() throws Exception {
        SimState state = seed();
        failures(state,3,"101");
        Map<String,Object> row = enqueue(state);
        for (long seconds : new long[]{0,30,90}) NotificationModule.drainDue(context(state,null,seconds));
        NotificationModule.retry(context(state,"701",100),s(row,"id"),map("version",n(row,"version"),"reason","修复后重试"));
        SimState baseline = copy(state);
        for (String kind : Arrays.asList("notificationAttempts","notificationRetryAudits")) {
            SimState removed = copy(baseline);
            removed.table(kind).remove(removed.table(kind).keySet().iterator().next());
            assertThrows(IllegalStateException.class,() -> JdbcSimStore.assertAppendOnly(baseline,removed),kind);
            SimState changed = copy(baseline);
            changed.table(kind).values().iterator().next().put("reason","改写历史");
            assertThrows(IllegalStateException.class,() -> JdbcSimStore.assertAppendOnly(baseline,changed),kind);
        }
    }

    @Test void fixtureFailuresAreTargetedAndSettingsUseOptimisticVersions() {
        SimState state = seed();
        SimContext controller = context(state,"701",0);
        Map<String,Object> initial = NotificationModule.failureConfiguration(controller);
        assertEquals(1,n(initial,"version"));
        assertEquals(0,n(initial,"remainingFailures"));
        assertTrue(state.table("notificationSettings").isEmpty());
        failures(state,1,"101");
        NotificationModule.enqueue(controller,"102","另一用户","receivables","8002","CREATED_V1",1);
        Map<String,Object> failed = enqueue(state);
        NotificationModule.drainDue(context(state,null,0));
        assertEquals(1,state.table("notifications").size());
        assertEquals("102",s(state.table("notifications").values().iterator().next(),"userId"));
        assertEquals("RETRY_WAIT",s(failed,"status"));
        assertEquals(0,n(NotificationModule.failureConfiguration(controller),"remainingFailures"));
        assertEquals("VERSION_CONFLICT",assertThrows(SimException.class,
                () -> NotificationModule.configureFailure(controller,map("version",1,"remainingFailures",0,"reason","过期设置"))).code);
        assertEquals("VALIDATION_FAILED",assertThrows(SimException.class,
                () -> NotificationModule.configureFailure(controller,map("version",3,"remainingFailures",101,"reason","超额"))).code);
        NotificationModule.drainDue(context(state,null,30));
        assertEquals("DELIVERED",s(failed,"status"));
        assertEquals(2,state.table("notifications").size());
    }

    @Test void recipientVisibilityAndGlobalControllerScopeAreEnforced() {
        SimState state = seed();
        Map<String,Object> row = enqueue(state);
        SimContext other = context(state,"102",0);
        SimContext controller = context(state,"701",0);
        other.create("grants",map("userId","102","role","SIM_CONTROLLER","merchantUid","s00001","status","ACTIVE"));
        assertEquals(1,NotificationModule.visibleOutbox(context(state,"101",0)).size());
        assertTrue(NotificationModule.visibleOutbox(other).isEmpty());
        assertEquals(1,NotificationModule.visibleOutbox(controller).size());
        assertEquals(404,assertThrows(SimException.class,
                () -> NotificationModule.visibleAttempts(other,s(row,"id"))).status);
        assertEquals(404,assertThrows(SimException.class,
                () -> NotificationModule.visibleRetryAudits(other,s(row,"id"))).status);
        assertEquals("SCOPE_DENIED",assertThrows(SimException.class,
                () -> NotificationModule.configureFailure(other,map("version",1,"remainingFailures",3,"reason","越权"))).code);
        assertEquals("SCOPE_DENIED",assertThrows(SimException.class,
                () -> NotificationModule.retry(other,s(row,"id"),map("version",1,"reason","越权"))).code);
        assertEquals("AUTH_REQUIRED",assertThrows(SimException.class,
                () -> NotificationModule.visibleOutbox(context(state,null,0))).code);
        state.meta.put("fixtureMode",false);
        assertEquals("SCOPE_DENIED",assertThrows(SimException.class,
                () -> NotificationModule.configureFailure(controller,map("version",1,"remainingFailures",3,"reason","非夹具"))).code);
        assertEquals("SCOPE_DENIED",assertThrows(SimException.class,
                () -> NotificationModule.retry(controller,s(row,"id"),map("version",1,"reason","非夹具"))).code);
    }

    @Test void disablingFixtureModeDisablesPersistedFailureInjection() {
        SimState state = seed();
        failures(state,3,"101");
        Map<String,Object> row = enqueue(state);
        state.meta.put("fixtureMode",false);
        NotificationModule.drainDue(context(state,null,0));
        assertEquals("DELIVERED",s(row,"status"));
        assertEquals(3,n(NotificationModule.failureConfiguration(context(state,"701",0)),"remainingFailures"));
    }

    @Test void missingRecipientBecomesDeadLetterWithoutThrowing() {
        SimState state = seed();
        Map<String,Object> row = NotificationModule.enqueue(context(state,null,0),null,"未分配接收人",
                "differences","9001","OVERDUE_V1",2);
        for (long seconds : new long[]{0,30,90})
            assertDoesNotThrow(() -> NotificationModule.drainDue(context(state,null,seconds)));
        assertEquals("DEAD",s(row,"status"));
        assertEquals("RECIPIENT_UNAVAILABLE",s(row,"lastError"));
        assertTrue(state.table("notifications").isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test void frozenNotificationDtoAndIdempotentReadArePreserved() throws Exception {
        SimState state = seed();
        Map<String,Object> outbox = enqueue(state);
        NotificationModule.drainDue(context(state,null,0));
        SimState before = copy(state);
        String id = s(outbox,"notificationId");
        SimContext recipient = context(state,"101",10);
        ReconciliationModule module = new ReconciliationModule();
        Map<String,Object> page = (Map<String,Object>)module.execute("listNotifications",recipient,map(),Collections.emptyMap());
        List<Map<String,Object>> items = list(page,"items");
        assertEquals(1,items.size());
        assertEquals(new HashSet<>(Arrays.asList("id","title","body","createdAt","read")),items.get(0).keySet());
        new ContractCatalog().validate("Notification",items.get(0));
        assertFalse(bool(items.get(0),"read"));
        assertEquals(404,assertThrows(SimException.class,() -> module.execute("readNotification",
                context(state,"102",10),map(),Collections.singletonMap("id",id))).status);
        module.execute("readNotification",recipient,map(),Collections.singletonMap("id",id));
        Map<String,Object> read = state.table("notifications").get(id);
        assertTrue(bool(read,"read"));
        assertEquals(2,n(read,"version"));
        assertEquals(start.plusSeconds(10).toString(),s(read,"readAt"));
        module.execute("readNotification",context(state,"101",20),map(),Collections.singletonMap("id",id));
        NotificationModule.drainDue(context(state,null,30));
        assertEquals(2,n(read,"version"));
        assertEquals(start.plusSeconds(10).toString(),s(read,"readAt"));
        assertEquals(json.writeValueAsString(before.table("notificationAttempts")),
                json.writeValueAsString(state.table("notificationAttempts")));
        assertEquals(1,state.table("notifications").size());
    }

    @Test void deliveryFailureDoesNotRollbackFulfillmentOrAccounting() throws Exception {
        EngineContractTest.F f = new EngineContractTest.F();
        f.setup();
        SimContext controller = new SimContext(f.store.state,"701",f.clock.instant());
        NotificationModule.configureFailure(controller,map("version",1,"remainingFailures",100,
                "userId","101","reason","验证业务成功与通知失败隔离"));
        Map<String,Object> receivable = f.fulfill();
        String journals = json.writeValueAsString(f.store.state.table("journals"));
        String lines = json.writeValueAsString(f.store.state.table("lines"));
        f.engine.sweep();
        assertEquals(2000,n(f.store.state.table("receivables").get(s(receivable,"id")),"outstandingMinor"));
        assertEquals(journals,json.writeValueAsString(f.store.state.table("journals")));
        assertEquals(lines,json.writeValueAsString(f.store.state.table("lines")));
        assertTrue(f.store.state.table("notificationOutbox").values().stream()
                .anyMatch(row -> "101".equals(s(row,"userId")) && "RETRY_WAIT".equals(s(row,"status"))));
        assertTrue(f.store.state.table("notifications").values().stream()
                .noneMatch(row -> "101".equals(s(row,"userId"))));
    }
}
