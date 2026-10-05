package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static cn.iocoder.yudao.server.simulation.SimContext.*;

/**
 * Transactional simulation aggregate store attached to Yudao's existing datasource.
 * A partition row lock serializes mutations across processes. Append-only projections
 * provide independent journal/audit evidence. This is not the final 38-table migration.
 */
public final class JdbcSimStore implements SimStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json=new ObjectMapper();
    public JdbcSimStore(DataSource source){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));initialize();}
    private void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS sim_v1_partition (id INT PRIMARY KEY, revision BIGINT NOT NULL, payload LONGTEXT NOT NULL, updated_at DATETIME(3) NOT NULL, CONSTRAINT sim_v1_payload_valid CHECK(JSON_VALID(payload))) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sim_v1_journal (id BIGINT PRIMARY KEY,event_key VARCHAR(180) NOT NULL UNIQUE,book_id BIGINT NOT NULL,template_code VARCHAR(20) NOT NULL,payload LONGTEXT NOT NULL,payload_hash CHAR(64) NOT NULL,created_at DATETIME(3) NOT NULL,CONSTRAINT sim_v1_journal_json CHECK(JSON_VALID(payload))) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sim_v1_journal_line (id BIGINT PRIMARY KEY,journal_id BIGINT NOT NULL,account_code VARCHAR(80) NOT NULL,side VARCHAR(8) NOT NULL,amount_minor BIGINT NOT NULL,payload LONGTEXT NOT NULL,payload_hash CHAR(64) NOT NULL,CONSTRAINT sim_v1_line_side CHECK(side IN ('DR','CR')),CONSTRAINT sim_v1_line_amount CHECK(amount_minor > 0),CONSTRAINT sim_v1_line_journal FOREIGN KEY(journal_id) REFERENCES sim_v1_journal(id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sim_v1_audit (id BIGINT PRIMARY KEY,request_id VARCHAR(36),action VARCHAR(120) NOT NULL,payload LONGTEXT NOT NULL,payload_hash CHAR(64) NOT NULL,created_at DATETIME(3) NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sim_v1_recon_difference (id BIGINT PRIMARY KEY,status VARCHAR(30) NOT NULL,assignee_id BIGINT NULL,checker_id BIGINT NULL,payload LONGTEXT NOT NULL,CONSTRAINT sim_v1_difference_closed_review CHECK(status <> 'CLOSED' OR (assignee_id IS NOT NULL AND checker_id IS NOT NULL AND checker_id <> assignee_id))) ENGINE=InnoDB");
        jdbc.update("INSERT IGNORE INTO sim_v1_partition(id,revision,payload,updated_at) VALUES(1,0,?,UTC_TIMESTAMP(3))",write(SimulationSeed.create(Instant.now())));
    }
    public <T> T transaction(Function<SimState,T> work){
        return tx.execute(status->{
            String raw=jdbc.queryForObject("SELECT payload FROM sim_v1_partition WHERE id=1 FOR UPDATE",String.class);SimState state=read(raw);T result=work.apply(state);state.meta.remove("requestId");String next=write(state);
            if(!next.equals(raw)){assertAppendOnly(read(raw),state);materialize(state);jdbc.update("UPDATE sim_v1_partition SET payload=?,revision=revision+1,updated_at=UTC_TIMESTAMP(3) WHERE id=1",next);}return result;
        });
    }
    /** Existing accounting and review evidence may only be extended, never rewritten. */
    static void assertAppendOnly(SimState before, SimState after) {
        for (String kind : Arrays.asList("journals", "lines", "audit", "reportSnapshots", "identityChanges", "applicationMaterials", "applicationHistory", "notificationAttempts", "notificationRetryAudits", "supportMessages", "supportTicketReplies", "supportTicketHistory", "resourceHistory", "platformConfigHistory")) {
            Map<String, Map<String,Object>> previous = before.records.get(kind);
            if (previous == null || previous.isEmpty()) continue;
            Map<String, Map<String,Object>> current = after.records.get(kind);
            for (Map.Entry<String, Map<String,Object>> row : previous.entrySet()) {
                if (current == null || !Objects.equals(row.getValue(), current.get(row.getKey())))
                    throw new IllegalStateException("Append-only evidence mutation rejected: " + kind);
            }
        }
    }
    private void materialize(SimState state){
        SimContext c=new SimContext(state,null,Instant.now());
        for(Map<String,Object> j:c.all("journals")){
            long debit=0,credit=0;for(Map<String,Object> line:c.all("lines"))if(s(j,"id").equals(s(line,"journalId"))){if("DR".equals(s(line,"side")))debit=Math.addExact(debit,n(line,"amountMinor"));else if("CR".equals(s(line,"side")))credit=Math.addExact(credit,n(line,"amountMinor"));else throw new IllegalStateException("Invalid journal side");}
            if(debit!=credit || debit<=0)throw new IllegalStateException("Unbalanced or empty journal");String payload=write(j),hash=c.hash(payload);
            List<String> old=jdbc.queryForList("SELECT payload_hash FROM sim_v1_journal WHERE id=?",String.class,s(j,"id"));
            if(old.isEmpty())jdbc.update("INSERT INTO sim_v1_journal VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",s(j,"id"),s(j,"eventKey"),s(j,"bookId"),s(j,"templateCode"),payload,hash);else if(!hash.equals(old.get(0)))throw new IllegalStateException("Posted journal mutation rejected");
        }
        for(Map<String,Object> l:c.all("lines")){
            String payload=write(l),hash=c.hash(payload);List<String> old=jdbc.queryForList("SELECT payload_hash FROM sim_v1_journal_line WHERE id=?",String.class,s(l,"id"));
            if(old.isEmpty())jdbc.update("INSERT INTO sim_v1_journal_line VALUES(?,?,?,?,?,?,?)",s(l,"id"),s(l,"journalId"),s(l,"accountCode"),s(l,"side"),n(l,"amountMinor"),payload,hash);else if(!hash.equals(old.get(0)))throw new IllegalStateException("Posted line mutation rejected");
        }
        for(Map<String,Object> a:c.all("audit")){
            String payload=write(a),hash=c.hash(payload);List<String> old=jdbc.queryForList("SELECT payload_hash FROM sim_v1_audit WHERE id=?",String.class,s(a,"id"));
            if(old.isEmpty())jdbc.update("INSERT INTO sim_v1_audit VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))",s(a,"id"),s(a,"requestId"),s(a,"action"),payload,hash);else if(!hash.equals(old.get(0)))throw new IllegalStateException("Audit mutation rejected");
        }
        for(Map<String,Object> d:c.all("differences"))jdbc.update("INSERT INTO sim_v1_recon_difference VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),assignee_id=VALUES(assignee_id),checker_id=VALUES(checker_id),payload=VALUES(payload)",s(d,"id"),s(d,"status"),d.get("assigneeId"),d.get("checkerId"),write(d));
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private SimState read(String value){try{return json.readValue(value,SimState.class);}catch(Exception e){throw new IllegalStateException("Simulation partition cannot be parsed",e);}}
}
