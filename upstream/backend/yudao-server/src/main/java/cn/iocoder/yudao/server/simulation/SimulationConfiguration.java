package cn.iocoder.yudao.server.simulation;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.*;
import javax.sql.DataSource;
import java.time.Clock;

@Configuration
@EnableScheduling
public class SimulationConfiguration {
    @Bean public SimStore simulationStore(DataSource dataSource,@Value("${simulation.environment:UNCONFIGURED}") String environment,@Value("${simulation.real-funds-enabled:true}") boolean realFunds){
        if(!"SIMULATION".equals(environment)||realFunds)throw new IllegalStateException("Isolated simulation configuration required");return new JdbcSimStore(dataSource);
    }
    @Bean public SimulationEngine simulationEngine(SimStore store,@Value("${simulation.mock-hmac-key-id:}") String id,@Value("${simulation.mock-hmac-key-base64:}") String encoded){MockKeyRegistry keys=new MockKeyRegistry();if(!encoded.isEmpty())keys.add(id,java.util.Base64.getDecoder().decode(encoded),java.time.Instant.EPOCH,java.time.Instant.parse("2100-01-01T00:00:00Z"));return new SimulationEngine(store,Clock.systemUTC(),keys);}
    @Bean public FilterRegistrationBean<SimulationApiFilter> simulationApiFilter(SimulationEngine engine){FilterRegistrationBean<SimulationApiFilter> r=new FilterRegistrationBean<>();r.setFilter(new SimulationApiFilter(engine));r.addUrlPatterns("/api/v1/*");r.setOrder(Ordered.HIGHEST_PRECEDENCE+60);return r;}
    @Bean public SimulationExpiry simulationExpiry(SimulationEngine engine){return new SimulationExpiry(engine);}
    public static final class SimulationExpiry {
        private final SimulationEngine engine;SimulationExpiry(SimulationEngine engine){this.engine=engine;}
        @Scheduled(fixedDelay=10000,initialDelay=10000) public void tick(){engine.sweep();}
    }
}
