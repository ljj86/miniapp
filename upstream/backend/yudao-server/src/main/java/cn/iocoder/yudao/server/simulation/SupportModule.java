package cn.iocoder.yudao.server.simulation;

import java.util.*;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/** Scoped shop conversations and multi-reply tickets in the transactional simulation store. */
public final class SupportModule {
    private static final List<String> STATUSES=Arrays.asList("OPEN","IN_PROGRESS","RESOLVED","CLOSED");
    private SupportModule() {}

    public static Object execute(String op,SimContext c,Map<String,Object> b,Map<String,String> p){
        c.requireRoles("USER");
        switch(op){
            case "support.context":return context(c);
            case "support.overview":return overview(c,p);
            case "support.session":return sessionDetail(c,p);
            case "support.ticket":return ticketView(c,require(c,"ticket",p.get("id")));
            case "support.open":return open(c,b);
            case "support.send":return send(c,b);
            case "support.read":return markRead(c,b);
            case "support.createTicket":return createTicket(c,b);
            case "support.reply":return reply(c,b);
            case "support.status":return status(c,b);
            case "support.archive":return archive(c,b,true);
            case "support.restore":return archive(c,b,false);
            default:throw new SimException(404,"RESOURCE_NOT_FOUND","客服操作不存在");
        }
    }
    public static boolean isPlatform(SimContext c){return c.inScope(null,null,null,"SUPPORT");}
    private static boolean isCustomer(SimContext c){return "ROLE_USER".equals(role(c));}
    static String role(SimContext c){return isPlatform(c)?"ROLE_ADMIN":c.hasRole("OWNER")||c.hasRole("CLERK")?"ROLE_UNIT":"ROLE_USER";}
    private static boolean isOwner(SimContext c,Map<String,Object> r){return c.actorId().equals(s(r,"userId"));}
    private static boolean scoped(SimContext c,Map<String,Object> r){
        return isPlatform(c)||isOwner(c,r)||c.inScope(s(r,"merchantUid"),s(r,"unitId"),null,"OWNER","CLERK");
    }
    public static boolean canReadResource(SimContext c,String resourceType,String resourceId){
        if(!Arrays.asList("session","ticket").contains(resourceType))return false;
        Map<String,Object> row=c.state.table("session".equals(resourceType)?"supportSessions":"supportTickets").get(resourceId);
        return row!=null && scoped(c,row) && (!bool(row,"archived")||isPlatform(c)||isOwner(c,row));
    }
    private static Map<String,Object> require(SimContext c,String type,String id){
        c.check(id!=null && canReadResource(c,type,id),404,"RESOURCE_NOT_FOUND","资源不存在或不可访问");
        return c.get("session".equals(type)?"supportSessions":"supportTickets",id);
    }
    private static Map<String,Object> store(SimContext c,String id){
        Map<String,Object> shop=c.get("stores",id);
        c.check(bool(shop,"active")&&"APPROVED".equals(s(IdentityModule.merchant(c,s(shop,"merchantUid")),"status")),409,"SUPPORT_SHOP_UNAVAILABLE","店铺暂不接受新客服请求");return shop;
    }
    private static Map<String,Object> context(SimContext c){
        List<Map<String,Object>> shops=new ArrayList<>(),orders=new ArrayList<>();String role=role(c);
        for(Map<String,Object> shop:c.all("stores")){
            boolean own=c.inScope(s(shop,"merchantUid"),s(shop,"id"),null,"OWNER","CLERK");
            boolean available=bool(shop,"active")&&"APPROVED".equals(s(IdentityModule.merchant(c,s(shop,"merchantUid")),"status"));
            if(isPlatform(c)||("ROLE_UNIT".equals(role)?own:available)){
                Map<String,Object> dto=c.project(shop,"id","merchantUid","name","active");dto.put("unitId",s(shop,"id"));dto.put("acceptingRequests",available);shops.add(dto);
            }
        }
        for(Map<String,Object> order:c.all("orders"))if(c.actorUid().equals(s(order,"userUid"))){
            Map<String,Object> dto=c.project(order,"id","status","createdAt","totalMinor");dto.put("unitId",s(order,"storeId"));dto.put("name","模拟订单 "+s(order,"id"));orders.add(dto);
        }
        return map("actor",map("id",c.actorId(),"uid",c.actorUid(),"name",SupportProfileModule.name(c,c.actorId()),"role",role),"shops",shops,"orders",orders,
            "capabilities",map("profileEditing",true,"newCustomerRequest",isCustomer(c),"platformConfiguration",isPlatform(c),"attachments",true,"maxAttachmentCount",5,"maxAttachmentBytes",524288,"maxAttachmentsPerCommandBytes",1048576,"storage","JDBC_SIMULATION_AGGREGATE","productionAuthentication",false,"externalCallsEnabled",false));
    }
    private static Map<String,Object> open(SimContext c,Map<String,Object> b){
        c.check(isCustomer(c),403,"SCOPE_DENIED","仅顾客可发起店铺会话");Map<String,Object> shop=store(c,s(b,"unitId"));
        for(Map<String,Object> row:c.all("supportSessions"))if(isOwner(c,row)&&s(shop,"id").equals(s(row,"unitId"))&&"ACTIVE".equals(s(row,"status")))return sessionView(c,row);
        Map<String,Object> row=c.create("supportSessions",map("userId",c.actorId(),"unitId",s(shop,"id"),"merchantUid",s(shop,"merchantUid"),"status","ACTIVE","readCursors",map()));
        c.audit("support.session.open","supportSession",s(row,"id"),map("storeId",s(shop,"id")));return sessionView(c,row);
    }
    private static Map<String,Object> send(SimContext c,Map<String,Object> b){
        Map<String,Object> session=require(c,"session",s(b,"sessionId"));c.check("ACTIVE".equals(s(session,"status")),409,"SUPPORT_SESSION_CLOSED","会话已关闭");
        String text=clean(c,b.get("text"),1000,true);List<String> ids=list(b,"attachmentIds");c.check(!text.isEmpty()||!ids.isEmpty(),422,"VALIDATION_FAILED","请填写消息或添加附件");
        List<Map<String,Object>> attachments=SupportAttachments.attach(c,ids,"session",s(session,"id"));
        Map<String,Object> row=map("sessionId",s(session,"id"),"text",text,"attachmentIds",new ArrayList<>(ids));row.putAll(sender(c));row=c.create("supportMessages",row);c.bump(session);
        Map<String,Object> out=messageView(c,row);out.put("attachments",attachments);return out;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> markRead(SimContext c,Map<String,Object> b){
        Map<String,Object> session=require(c,"session",s(b,"sessionId"));Map<String,Object> message=c.get("supportMessages",s(b,"lastSeenMessageId"));
        c.check(s(session,"id").equals(s(message,"sessionId")),404,"RESOURCE_NOT_FOUND","消息不存在或不可访问");
        Map<String,Object> cursors=(Map<String,Object>)session.get("readCursors");long previous=n(cursors,c.actorId()),next=n(message,"id");
        if(next>previous){cursors.put(c.actorId(),next);c.bump(session);}return sessionView(c,session);
    }
    private static Map<String,Object> createTicket(SimContext c,Map<String,Object> b){
        c.check(isCustomer(c),403,"SCOPE_DENIED","仅顾客可提交留言");Map<String,Object> shop=store(c,s(b,"unitId"));
        String orderId=s(b,"orderId");if(orderId!=null){Map<String,Object> order=c.get("orders",orderId);c.check(c.actorUid().equals(s(order,"userUid"))&&s(shop,"id").equals(s(order,"storeId")),403,"SCOPE_DENIED","关联订单须属于本人和当前店铺");}
        Map<String,Object> ticket=c.create("supportTickets",map("userId",c.actorId(),"unitId",s(shop,"id"),"merchantUid",s(shop,"merchantUid"),"orderId",orderId,
            "title",clean(c,b.get("title"),200,false),"content",clean(c,b.get("content"),1500,false),"priority",priority(c,s(b,"priority")),"status","OPEN","archived",false,"replyCount",0L,"historyCount",0L,"attachmentIds",new ArrayList<>(SupportModule.<String>values(b,"attachmentIds"))));
        SupportAttachments.attach(c,list(ticket,"attachmentIds"),"ticket",s(ticket,"id"));history(c,ticket,"CREATE",null,"OPEN","提交留言");return mutationTicket(c,ticket);
    }
    private static <T> List<T> values(Map<String,Object> b,String key){return list(b,key);}
    private static Map<String,Object> reply(SimContext c,Map<String,Object> b){
        Map<String,Object> ticket=require(c,"ticket",s(b,"ticketId"));c.version(ticket,b);editable(c,ticket);
        c.check(!"CLOSED".equals(s(ticket,"status")),409,"INVALID_TRANSITION","已关闭留言请先重新打开");
        List<String> ids=list(b,"attachmentIds");SupportAttachments.attach(c,ids,"ticket",s(ticket,"id"));
        Map<String,Object> row=map("ticketId",s(ticket,"id"),"text",clean(c,b.get("text"),1000,false),"attachmentIds",new ArrayList<>(ids));row.putAll(sender(c));ticket.put("replyCount",count(c,ticket,"supportTicketReplies","replyCount")+1);Map<String,Object> reply=c.create("supportTicketReplies",row);c.bump(ticket);
        history(c,ticket,"REPLY",s(ticket,"status"),s(ticket,"status"),"添加回复").put("replyId",s(reply,"id"));return mutationTicket(c,ticket);
    }
    private static Map<String,Object> status(SimContext c,Map<String,Object> b){
        Map<String,Object> ticket=require(c,"ticket",s(b,"ticketId"));c.version(ticket,b);editable(c,ticket);String before=s(ticket,"status"),next=s(b,"status");
        c.check(availableStatuses(c,ticket).contains(next),409,"INVALID_TRANSITION","当前身份不能执行此状态变更");ticket.put("status",next);c.bump(ticket);history(c,ticket,"STATUS",before,next,"更新留言状态");return mutationTicket(c,ticket);
    }
    private static Map<String,Object> archive(SimContext c,Map<String,Object> b,boolean archived){
        Map<String,Object> ticket=require(c,"ticket",s(b,"ticketId"));c.check(isPlatform(c)||isOwner(c,ticket),403,"SCOPE_DENIED","仅留言本人或平台可归档和恢复");c.version(ticket,b);
        c.check(bool(ticket,"archived")!=archived,409,"INVALID_TRANSITION",archived?"留言已归档":"留言未归档");String reason=clean(c,b.get("reason"),500,false);
        ticket.put("archived",archived);ticket.put("archivedBy",archived?c.actorId():null);ticket.put("archivedAt",archived?c.now().toString():null);c.bump(ticket);history(c,ticket,archived?"ARCHIVE":"RESTORE",s(ticket,"status"),s(ticket,"status"),reason);return mutationTicket(c,ticket);
    }
    private static void editable(SimContext c,Map<String,Object> row){c.check(!bool(row,"archived"),409,"SUPPORT_ARCHIVED","请先恢复已归档留言");}
    private static Map<String,Object> history(SimContext c,Map<String,Object> ticket,String type,String from,String to,String text){
        ticket.put("historyCount",count(c,ticket,"supportTicketHistory","historyCount")+1);
        Map<String,Object> row=map("ticketId",s(ticket,"id"),"ticketVersion",n(ticket,"version"),"type",type,"fromStatus",from,"toStatus",to,"text",text,"actorId",c.actorId(),"actorRole",role(c),"actorName",SupportProfileModule.name(c,c.actorId()),"actorLabel",label(role(c)));
        c.audit("support.ticket."+type.toLowerCase(Locale.ROOT),"supportTicket",s(ticket,"id"),map("fromStatus",from,"toStatus",to,"version",n(ticket,"version")));return c.create("supportTicketHistory",row);
    }
    private static Map<String,Object> sender(SimContext c){String role=role(c);return map("senderId",c.actorId(),"senderRole",role,"senderName",SupportProfileModule.name(c,c.actorId()),"senderLabel",label(role),"senderAvatar","/avatar.svg");}
    private static String label(String role){return "ROLE_ADMIN".equals(role)?"平台客服":"ROLE_UNIT".equals(role)?"商家客服":"顾客";}
    private static String clean(SimContext c,Object value,int max,boolean empty){
        c.check(value instanceof String || empty&&value==null,422,"VALIDATION_FAILED","须提交纯文本");String text=value==null?"":((String)value).replace("\r\n","\n").replace('\r','\n').trim();
        c.check((empty||!text.isEmpty())&&text.codePointCount(0,text.length())<=max&&!text.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f].*"),422,"VALIDATION_FAILED","文本为空、过长或包含控制字符");return text;
    }
    private static String priority(SimContext c,String value){if("NORMAL".equals(value))value="MEDIUM";if("IMPORTANT".equals(value))value="HIGH";c.check(Arrays.asList("LOW","MEDIUM","HIGH").contains(value),422,"VALIDATION_FAILED","无效留言等级");return value;}
    private static List<String> availableStatuses(SimContext c,Map<String,Object> ticket){
        if(bool(ticket,"archived"))return new ArrayList<>();String status=s(ticket,"status");List<String> result=new ArrayList<>();
        if(isOwner(c,ticket)&&!isPlatform(c)&&!c.inScope(s(ticket,"merchantUid"),s(ticket,"unitId"),null,"OWNER","CLERK")){
            if("RESOLVED".equals(status))result.add("CLOSED");
        }else{int i=STATUSES.indexOf(status);if(i>=0&&i<STATUSES.size()-1)result.add(STATUSES.get(i+1));}
        if(Arrays.asList("RESOLVED","CLOSED").contains(status))result.add("OPEN");return result;
    }
    private static Map<String,Object> display(SimContext c,Map<String,Object> row){
        Map<String,Object> user=c.get("users",s(row,"userId")),shop=c.get("stores",s(row,"unitId"));return map("customerName",SupportProfileModule.name(c,s(row,"userId")),"customerAvatar","/avatar.svg","shopName",s(shop,"name"),"shopAvatar","/avatar.svg");
    }
    private static Map<String,Object> messageView(SimContext c,Map<String,Object> row){
        Map<String,Object> out=c.project(row,"id","sessionId","ticketId","text","senderId","senderRole","senderName","senderLabel","senderAvatar","createdAt");out.put("sequence",n(row,"id"));
        List<Map<String,Object>> attachments=SupportAttachments.describe(c,list(row,"attachmentIds"));out.put("attachments",attachments);out.put("attachment",attachments.isEmpty()?null:attachments.get(0));return out;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> sessionView(SimContext c,Map<String,Object> row){
        Map<String,Object> out=c.project(row,"id","userId","unitId","merchantUid","status","version","createdAt","updatedAt");out.putAll(display(c,row));Map<String,Object> latest=null;long unread=0,cursor=n((Map)row.get("readCursors"),c.actorId());
        for(Map<String,Object> message:c.all("supportMessages"))if(s(row,"id").equals(s(message,"sessionId"))){if(latest==null||n(message,"id")>n(latest,"id"))latest=message;if(n(message,"id")>cursor&&!c.actorId().equals(s(message,"senderId")))unread++;}
        out.put("lastMessage",latest==null?null:messageView(c,latest));out.put("unread",unread);out.put("unreadCount",unread);return out;
    }
    private static long count(SimContext c,Map<String,Object> ticket,String table,String field){
        if(ticket.containsKey(field))return n(ticket,field);long count=0;for(Map<String,Object> row:c.all(table))if(s(ticket,"id").equals(s(row,"ticketId")))count++;return count;
    }
    private static Map<String,Object> mutationTicket(SimContext c,Map<String,Object> row){Map<String,Object> out=ticketView(c,row,false);out.put("detailRequired",true);return out;}
    private static Map<String,Object> ticketView(SimContext c,Map<String,Object> row){return ticketView(c,row,true);}
    private static Map<String,Object> ticketView(SimContext c,Map<String,Object> row,boolean details){
        Map<String,Object> out=c.project(row,"id","userId","unitId","merchantUid","orderId","title","content","priority","status","version","createdAt","updatedAt","archived","archivedAt","archivedBy");out.putAll(display(c,row));
        List<Map<String,Object>> attachments=SupportAttachments.describe(c,list(row,"attachmentIds"));out.put("attachments",attachments);out.put("attachment",attachments.isEmpty()?null:attachments.get(0));
        if(details){List<Map<String,Object>> replies=new ArrayList<>(),history=new ArrayList<>();for(Map<String,Object> r:c.all("supportTicketReplies"))if(s(row,"id").equals(s(r,"ticketId")))replies.add(messageView(c,r));
        for(Map<String,Object> r:c.all("supportTicketHistory"))if(s(row,"id").equals(s(r,"ticketId")))history.add(c.project(r,"id","type","text","actorId","actorRole","actorName","actorLabel","createdAt","fromStatus","toStatus","ticketVersion","replyId"));
        out.put("replies",replies);out.put("history",history);}
        out.put("replyCount",count(c,row,"supportTicketReplies","replyCount"));out.put("historyCount",count(c,row,"supportTicketHistory","historyCount"));out.put("availableStatuses",availableStatuses(c,row));out.put("canReply",!bool(row,"archived")&&!"CLOSED".equals(s(row,"status")));out.put("canArchive",!bool(row,"archived")&&(isOwner(c,row)||isPlatform(c)));out.put("canRestore",bool(row,"archived")&&(isOwner(c,row)||isPlatform(c)));return out;
    }
    private static Map<String,Object> sessionDetail(SimContext c,Map<String,String> params){
        Map<String,Object> session=require(c,"session",params.get("id"));long before=Long.MAX_VALUE;if(params.get("beforeId")!=null){String id=params.get("beforeId");c.check(id.matches("[1-9][0-9]{0,19}"),422,"VALIDATION_FAILED","无效消息游标");Map<String,Object> marker=c.get("supportMessages",id);c.check(s(session,"id").equals(s(marker,"sessionId")),404,"RESOURCE_NOT_FOUND","消息不存在或不可访问");before=n(marker,"id");}
        List<Map<String,Object>> messages=new ArrayList<>();for(Map<String,Object> message:c.all("supportMessages"))if(s(session,"id").equals(s(message,"sessionId"))&&n(message,"id")<before)messages.add(messageView(c,message));
        messages.sort(Comparator.comparingLong(a->n(a,"id")));boolean more=messages.size()>100;return map("session",sessionView(c,session),"messages",new ArrayList<>(messages.subList(Math.max(0,messages.size()-100),messages.size())),"hasMore",more);
    }
    private static Map<String,Object> overview(SimContext c,Map<String,String> p){
        String status=p.get("status"),priority=p.get("priority"),archived=p.get("includeArchived"),query=p.get("q"),unitId=p.get("unitId");
        c.check(query==null||(!query.trim().isEmpty()&&query.codePointCount(0,query.length())<=100),422,"VALIDATION_FAILED","关键词须为1至100字");c.check(unitId==null||unitId.matches("[1-9][0-9]{0,19}"),422,"VALIDATION_FAILED","无效店铺编号");if(status!=null)c.check(STATUSES.contains(status),422,"VALIDATION_FAILED","无效状态");if(priority!=null)priority=priority(c,priority);
        c.check(archived==null||Arrays.asList("true","false").contains(archived),422,"VALIDATION_FAILED","无效归档筛选");boolean includeArchived="true".equals(archived);int page=number(c,p.get("page"),1,100000),limit=number(c,p.get("limit"),20,100);c.check(Arrays.asList(10,20,50,100).contains(limit),422,"VALIDATION_FAILED","每页数量须为10、20、50或100");
        List<Map<String,Object>> sessions=new ArrayList<>(),tickets=new ArrayList<>();long unread=0,unreadSessions=0,open=0,progress=0,resolved=0,closed=0,archiveCount=0;
        for(Map<String,Object> row:c.all("supportSessions"))if(canReadResource(c,"session",s(row,"id"))&&matches(c,row,unitId,query)){Map<String,Object> dto=sessionView(c,row);sessions.add(dto);unread+=n(dto,"unread");if(n(dto,"unread")>0)unreadSessions++;}
        for(Map<String,Object> row:c.all("supportTickets"))if(canReadResource(c,"ticket",s(row,"id"))&&matches(c,row,unitId,query)){
            if(bool(row,"archived")){archiveCount++;if(!includeArchived)continue;}
            if(status!=null&&!status.equals(s(row,"status"))||priority!=null&&!priority.equals(s(row,"priority")))continue;
            tickets.add(ticketView(c,row,false));switch(s(row,"status")){case "OPEN":open++;break;case "IN_PROGRESS":progress++;break;case "RESOLVED":resolved++;break;case "CLOSED":closed++;break;default:break;}
        }
        Comparator<Map<String,Object>> newest=(a,b)->{int date=s(b,"updatedAt").compareTo(s(a,"updatedAt"));return date!=0?date:Long.compare(n(b,"id"),n(a,"id"));};sessions.sort(newest);tickets.sort(newest);
        return map("sessions",slice(sessions,page,limit),"tickets",slice(tickets,page,limit),"counts",map("sessions",sessions.size(),"tickets",tickets.size(),"unreadMessages",unread,"unreadSessions",unreadSessions,"openTickets",open,"inProgressTickets",progress,"resolvedTickets",resolved,"closedTickets",closed,"archivedTickets",archiveCount),"page",page,"limit",limit,"sessionTotal",sessions.size(),"ticketTotal",tickets.size(),"truncated",sessions.size()>limit||tickets.size()>limit,"hasMore",page*limit<sessions.size()||page*limit<tickets.size());
    }
    private static boolean matches(SimContext c,Map<String,Object> row,String unitId,String query){
        if(unitId!=null&&!unitId.equals(s(row,"unitId")))return false;if(query==null)return true;
        Map<String,Object> display=display(c,row);String haystack=String.valueOf(row.get("title"))+"\n"+String.valueOf(row.get("content"))+"\n"+s(display,"customerName")+"\n"+s(display,"shopName");
        return haystack.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }
    private static int number(SimContext c,String input,int fallback,int max){if(input==null)return fallback;try{int n=Integer.parseInt(input);c.check(n>=1&&n<=max,422,"VALIDATION_FAILED","分页超出范围");return n;}catch(NumberFormatException e){throw new SimException(422,"VALIDATION_FAILED","分页须为整数");}}
    private static List<Map<String,Object>> slice(List<Map<String,Object>> rows,int page,int limit){int from=Math.min(rows.size(),(page-1)*limit);return new ArrayList<>(rows.subList(from,Math.min(rows.size(),from+limit)));}
}
