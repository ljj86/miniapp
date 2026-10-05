package cn.iocoder.yudao.server.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Clock;
import java.util.function.Function;
import javax.servlet.http.HttpServlet;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

/**
 * Loopback-only HTTP verification fixture. Uses the actual API filter/domain and a test-only
 * atomic JSON file store so restart behavior can be exercised without claiming MySQL coverage.
 * Never included in production classes; never use real accounts or expose it to a network.
 */
public final class SupportHttpFixture {
    public static final class AtomicFileStore implements SimStore {
        private final Path file;
        private final ObjectMapper json=new ObjectMapper();
        public AtomicFileStore(Path file,SimState initial)throws Exception{
            this.file=file.toAbsolutePath();Files.createDirectories(this.file.getParent());
            if(!Files.exists(this.file))Files.write(this.file,json.writeValueAsBytes(initial));
        }
        public synchronized <T>T transaction(Function<SimState,T> work){
            try{
                byte[] original=Files.readAllBytes(file);SimState before=json.readValue(original,SimState.class),working=json.readValue(original,SimState.class);
                T result=work.apply(working);working.meta.remove("requestId");JdbcSimStore.assertAppendOnly(before,working);
                byte[] next=json.writeValueAsBytes(working);if(!java.util.Arrays.equals(original,next)){
                    Path temporary=Files.createTempFile(file.getParent(),"support-state-",".tmp");
                    try{Files.write(temporary,next);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
                }return result;
            }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    public static Tomcat start(int port,SimStore store)throws Exception{
        Tomcat server=new Tomcat();server.setBaseDir(Files.createTempDirectory("support-tomcat-").toString());server.setPort(port);
        server.getConnector().setProperty("address","127.0.0.1");server.getConnector().setProperty("maxPostSize","1048576");
        Context context=server.addContext("",Files.createTempDirectory("support-webroot-").toString());
        Tomcat.addServlet(context,"empty",new HttpServlet(){});context.addServletMappingDecoded("/","empty");
        FilterDef filter=new FilterDef();filter.setFilterName("simulation");filter.setFilter(new SimulationApiFilter(new SimulationEngine(store,Clock.systemUTC())));context.addFilterDef(filter);
        FilterMap mapping=new FilterMap();mapping.setFilterName("simulation");mapping.addURLPattern("/api/v1/*");context.addFilterMapBefore(mapping);
        server.start();return server;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Usage: SupportHttpFixture <loopback-port> <test-state-file>");
        int port=Integer.parseInt(args[0]);if(port<1024||port>65535)throw new IllegalArgumentException("Use unprivileged loopback port");
        EngineContractTest.F fixture=new EngineContractTest.F();fixture.setup();
        // Existing seed is all synthetic. Clear short-lived request limits before real-clock HTTP tests.
        fixture.store.state.records.remove("rateLimits");
        Tomcat server=start(port,new AtomicFileStore(Paths.get(args[1]),fixture.store.state));
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{server.stop();server.destroy();}catch(Exception ignored){}}));
        System.out.println("SUPPORT_HTTP_FIXTURE_READY http://127.0.0.1:"+port+"/api/v1; TEST_ATOMIC_JSON_FILE (not MySQL)");
        server.getServer().await();
    }
}
