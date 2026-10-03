package cn.iocoder.yudao.server.simulation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Durable, in-process simulation delivery. This module never contacts an external service. */
public final class NotificationModule {
    private static final String SETTING_ID = "notification-delivery";
    private static final long MAX_CYCLE_ATTEMPTS = 3;
    private static final long MAX_FAILURES = 100;

    private NotificationModule() {}

    /** Enqueue in the business transaction; delivery starts in a subsequent sweep. */
    public static Map<String,Object> enqueue(SimContext c, String userId, String title,
                                            String resourceType, String resourceId,
                                            String template, long eventVersion) {
        String eventKey = c.hashObject(map("resourceType",resourceType,"resourceId",resourceId,
                "eventVersion",eventVersion));
        String dedupeKey = c.hashObject(map("eventKey",eventKey,"userId",userId,"template",template));
        for (Map<String,Object> row : c.all("notificationOutbox"))
            if (dedupeKey.equals(s(row,"dedupeKey"))) return row;
        return c.create("notificationOutbox",map("userId",userId,"title",title,"body",title,
                "resourceType",resourceType,"resourceId",resourceId,"eventVersion",eventVersion,
                "eventKey",eventKey,"template",template,"dedupeKey",dedupeKey,
                "status","PENDING","attemptCount",0L,"cycle",1L,"cycleAttemptCount",0L,
                "nextAttemptAt",c.now().toString(),"lastAttemptAt",null,"lastError",null,
                "deliveredAt",null,"deadAt",null,"notificationId",null,
                "environment","SIMULATION"));
    }

    /** Each due row receives at most one attempt per sweep; no missed-window burst retries. */
    public static int drainDue(SimContext c) {
        int attempted = 0;
        for (Map<String,Object> row : c.all("notificationOutbox")) {
            String status = s(row,"status");
            if (!("PENDING".equals(status) || "RETRY_WAIT".equals(status))) continue;
            if (row.get("nextAttemptAt") == null ||
                    c.now().isBefore(Instant.parse(s(row,"nextAttemptAt")))) continue;
            deliver(c,row);
            attempted++;
        }
        return attempted;
    }

    private static void deliver(SimContext c, Map<String,Object> row) {
        long sequence = Math.addExact(n(row,"attemptCount"),1L);
        long cycleAttempt = Math.addExact(n(row,"cycleAttemptCount"),1L);
        Map<String,Object> existing = deliveredNotification(c,row);
        String error = null;
        if (existing == null) {
            Map<String,Object> recipient = c.state.table("users").get(s(row,"userId"));
            if (recipient == null || !"ACTIVE".equals(s(recipient,"status")))
                error = "RECIPIENT_UNAVAILABLE";
            else if (consumeFailure(c,row)) error = "SIMULATED_DELIVERY_FAILURE";
            if (error == null) {
                existing = c.create("notifications",map("userId",s(row,"userId"),
                        "title",s(row,"title"),"body",s(row,"body"),"content",s(row,"body"),
                        "resourceType",s(row,"resourceType"),"resourceId",s(row,"resourceId"),
                        "eventKey",s(row,"eventKey"),"template",s(row,"template"),
                        "outboxId",s(row,"id"),"dedupeKey",s(row,"dedupeKey"),
                        "read",false,"readAt",null));
            }
        }
        row.put("attemptCount",sequence);
        row.put("cycleAttemptCount",cycleAttempt);
        row.put("lastAttemptAt",c.now().toString());
        row.put("lastError",error);
        if (error == null) {
            row.put("status","DELIVERED");
            row.put("notificationId",s(existing,"id"));
            row.put("deliveredAt",c.now().toString());
            row.put("nextAttemptAt",null);
            row.put("deadAt",null);
        } else if (cycleAttempt >= MAX_CYCLE_ATTEMPTS) {
            row.put("status","DEAD");
            row.put("nextAttemptAt",null);
            row.put("deadAt",c.now().toString());
        } else {
            row.put("status","RETRY_WAIT");
            row.put("nextAttemptAt",c.now().plusSeconds(cycleAttempt == 1 ? 30 : 60).toString());
        }
        c.bump(row);
        Map<String,Object> attempt = c.create("notificationAttempts",map("outboxId",s(row,"id"),
                "userId",s(row,"userId"),"sequence",sequence,"cycle",n(row,"cycle"),
                "cycleAttempt",cycleAttempt,"status",error == null ? "DELIVERED" : "FAILED",
                "errorCode",error,"outboxStatus",s(row,"status"),
                "nextAttemptAt",row.get("nextAttemptAt"),"notificationId",row.get("notificationId"),
                "occurredAt",c.now().toString(),"environment","SIMULATION"));
        c.audit("NOTIFICATION_DELIVERY_ATTEMPT","notificationOutbox",s(row,"id"),
                map("attemptId",s(attempt,"id"),"sequence",sequence,"cycle",n(row,"cycle"),
                        "cycleAttempt",cycleAttempt,"status",s(attempt,"status"),
                        "errorCode",error,"outboxStatus",s(row,"status")));
    }

    private static Map<String,Object> deliveredNotification(SimContext c, Map<String,Object> row) {
        for (Map<String,Object> notification : c.all("notifications"))
            if (Objects.equals(s(row,"dedupeKey"),s(notification,"dedupeKey"))) return notification;
        return null;
    }

    private static boolean consumeFailure(SimContext c, Map<String,Object> row) {
        if (!bool(c.state.meta,"fixtureMode")) return false;
        Map<String,Object> setting = c.state.table("notificationSettings").get(SETTING_ID);
        if (setting == null || n(setting,"remainingFailures") <= 0) return false;
        if (setting.get("userId") != null && !Objects.equals(s(setting,"userId"),s(row,"userId")))
            return false;
        setting.put("remainingFailures",n(setting,"remainingFailures") - 1);
        c.bump(setting);
        return true;
    }

    /** Unconfigured delivery has version 1 and zero failures. Reading does not create state. */
    public static Map<String,Object> failureConfiguration(SimContext c) {
        requireController(c);
        Map<String,Object> setting = c.state.table("notificationSettings").get(SETTING_ID);
        return setting == null ? map("id",SETTING_ID,"version",1L,"remainingFailures",0L,
                "fixtureOnly",true,"environment","SIMULATION") : settingDto(c,setting);
    }

    /** Body: version, remainingFailures (0..100), optional recipient userId, and reason. */
    public static Map<String,Object> configureFailure(SimContext c, Map<String,Object> body) {
        requireController(c);
        requireFixture(c);
        requireReason(c,body);
        c.check(body.containsKey("remainingFailures") && n(body,"remainingFailures") >= 0 &&
                n(body,"remainingFailures") <= MAX_FAILURES,422,"VALIDATION_FAILED",
                "模拟通知失败次数必须为0至100");
        String userId = s(body,"userId");
        if (userId != null) c.get("users",userId);
        Map<String,Object> setting = c.state.table("notificationSettings").get(SETTING_ID);
        c.version(setting == null ? map("version",1L) : setting,body);
        if (setting == null) setting = c.create("notificationSettings",map("id",SETTING_ID,
                "remainingFailures",0L,"fixtureOnly",true,"environment","SIMULATION"));
        setting.put("remainingFailures",n(body,"remainingFailures"));
        setting.put("userId",userId);
        setting.put("reason",s(body,"reason").trim());
        setting.put("configuredBy",c.actorId());
        c.bump(setting);
        c.audit("NOTIFICATION_FAILURE_CONFIGURED","notificationSettings",SETTING_ID,
                map("version",n(setting,"version"),"remainingFailures",n(setting,"remainingFailures"),
                        "userId",userId,"reason",s(setting,"reason")));
        return settingDto(c,setting);
    }

    /** Explicitly opens a new three-attempt cycle without changing any past attempt evidence. */
    public static Map<String,Object> retry(SimContext c, String id, Map<String,Object> body) {
        requireController(c);
        requireFixture(c);
        Map<String,Object> row = c.get("notificationOutbox",id);
        c.version(row,body);
        requireReason(c,body);
        c.check("DEAD".equals(s(row,"status")),409,"STATE_CONFLICT","仅死信可开始新的模拟通知重试周期");
        long previousVersion = n(row,"version"), previousCycle = n(row,"cycle");
        row.put("cycle",Math.addExact(previousCycle,1L));
        row.put("cycleAttemptCount",0L);
        row.put("status","PENDING");
        row.put("nextAttemptAt",c.now().toString());
        row.put("deadAt",null);
        c.bump(row);
        Map<String,Object> retry = c.create("notificationRetryAudits",map("outboxId",id,
                "userId",s(row,"userId"),"actorId",c.actorId(),"reason",s(body,"reason").trim(),
                "fromVersion",previousVersion,"toVersion",n(row,"version"),
                "fromCycle",previousCycle,"toCycle",n(row,"cycle"),"attemptCount",n(row,"attemptCount"),
                "maxCycleAttempts",MAX_CYCLE_ATTEMPTS,"requestId",c.state.meta.get("requestId"),
                "occurredAt",c.now().toString(),"environment","SIMULATION"));
        c.audit("NOTIFICATION_RETRY_REQUESTED","notificationOutbox",id,
                map("retryAuditId",s(retry,"id"),"reason",s(retry,"reason"),
                        "fromVersion",previousVersion,"toVersion",n(row,"version"),"cycle",n(row,"cycle")));
        return outboxDto(c,row);
    }

    /** Owners see only their rows; only a platform-wide controller can see every recipient. */
    public static List<Map<String,Object>> visibleOutbox(SimContext c) {
        c.requireRoles("USER");
        boolean controller = c.inScope(null,null,null,"SIM_CONTROLLER");
        List<Map<String,Object>> rows = new ArrayList<>();
        for (Map<String,Object> row : c.all("notificationOutbox"))
            if (controller || c.actorId().equals(s(row,"userId"))) rows.add(outboxDto(c,row));
        return rows;
    }

    public static List<Map<String,Object>> visibleAttempts(SimContext c, String id) {
        visibleRow(c,id);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (Map<String,Object> row : c.all("notificationAttempts"))
            if (id.equals(s(row,"outboxId"))) rows.add(c.project(row,"id","outboxId","sequence",
                    "cycle","cycleAttempt","status","errorCode","outboxStatus","nextAttemptAt",
                    "notificationId","occurredAt","createdAt","version","environment"));
        return rows;
    }

    public static List<Map<String,Object>> visibleRetryAudits(SimContext c, String id) {
        visibleRow(c,id);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (Map<String,Object> row : c.all("notificationRetryAudits"))
            if (id.equals(s(row,"outboxId"))) rows.add(c.project(row,"id","outboxId","actorId",
                    "reason","fromVersion","toVersion","fromCycle","toCycle","attemptCount",
                    "maxCycleAttempts","occurredAt","createdAt","version","environment"));
        return rows;
    }

    private static void visibleRow(SimContext c, String id) {
        c.requireRoles("USER");
        Map<String,Object> row = c.get("notificationOutbox",id);
        c.check(c.actorId().equals(s(row,"userId")) || c.inScope(null,null,null,"SIM_CONTROLLER"),
                404,"RESOURCE_NOT_FOUND","通知不可访问");
    }

    private static Map<String,Object> outboxDto(SimContext c, Map<String,Object> row) {
        return c.project(row,"id","userId","title","body","resourceType","resourceId",
                "eventVersion","template","status","attemptCount","cycle","cycleAttemptCount",
                "nextAttemptAt","lastAttemptAt","lastError","deliveredAt","deadAt","notificationId",
                "createdAt","updatedAt","version","environment");
    }

    private static Map<String,Object> settingDto(SimContext c, Map<String,Object> row) {
        return c.project(row,"id","version","remainingFailures","userId","reason","configuredBy",
                "createdAt","updatedAt","fixtureOnly","environment");
    }

    private static void requireController(SimContext c) {
        c.requireScope(null,null,null,"SIM_CONTROLLER");
    }

    private static void requireFixture(SimContext c) {
        c.check(bool(c.state.meta,"fixtureMode"),403,"SCOPE_DENIED","模拟夹具控制台不可用");
    }

    private static void requireReason(SimContext c, Map<String,Object> body) {
        String reason = s(body,"reason");
        c.check(reason != null && !reason.trim().isEmpty() && reason.length() <= 500,
                422,"VALIDATION_FAILED","需提供不超过500字符的明确原因");
    }
}
