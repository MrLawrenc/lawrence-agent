package dev.perflab.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Service
public class LabWork {
    private final String role,downstream,rabbitHost;
    private final int rabbitPort;
    private final JdbcTemplate jdbc;
    private final DataSource pool;
    private final ObjectMapper json;
    private final JedisPooled redis;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final org.apache.hc.client5.http.impl.classic.CloseableHttpClient apache=org.apache.hc.client5.http.impl.classic.HttpClients.createDefault();
    private final ExecutorService tasks=Executors.newFixedThreadPool(2);
    private final List<byte[]> retained=new ArrayList<>();
    public LabWork(JdbcTemplate jdbc,DataSource pool,ObjectMapper json,
            @Value("${LAB_SERVICE:gateway}") String role,@Value("${LAB_DOWNSTREAM:}") String downstream,
            @Value("${LAB_REDIS_HOST:redis}") String redisHost,@Value("${LAB_RABBIT_HOST:rabbitmq}") String rabbitHost,
            @Value("${LAB_REDIS_PORT:6379}") int redisPort,@Value("${LAB_RABBIT_PORT:5672}") int rabbitPort)throws SQLException{
        this.jdbc=jdbc;this.pool=pool;this.json=json;this.role=role;this.downstream=downstream;this.rabbitHost=rabbitHost;this.rabbitPort=rabbitPort;redis=new JedisPooled(redisHost,redisPort);
        // IF NOT EXISTS alone does not serialize concurrent PostgreSQL catalog creation.
        try(Connection connection=pool.getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){
                statement.execute("SELECT pg_advisory_xact_lock(73104219)");
                statement.execute("CREATE TABLE IF NOT EXISTS lab_items (id INTEGER PRIMARY KEY, name VARCHAR(80))");
                statement.executeUpdate("INSERT INTO lab_items(id,name) VALUES (1,'fixture-item') ON CONFLICT (id) DO NOTHING");
                connection.commit();
            }catch(SQLException failure){connection.rollback();throw failure;}
        }
    }
    public Map<String,Object> chain(String parent)throws Exception{
        List<Object> children=new ArrayList<>();
        if(!downstream.isBlank())for(int i=0;i<(role.equals("gateway")?2:1);i++){
            var result=http.send(HttpRequest.newBuilder(URI.create(downstream+"/chain")).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
            if(result.statusCode()!=200)throw new IllegalStateException("downstream "+result.statusCode());children.add(json.readValue(result.body(),Map.class));
        }
        return Map.of("service",role,"incomingTraceparent",parent==null?"":parent,"children",children);
    }
    public Map<String,Object> sql() {
        long start=System.nanoTime();jdbc.execute("SELECT pg_sleep(0.08)");
        return Map.of("elapsedMillis",(System.nanoTime()-start)/1_000_000.0,"name",jdbc.queryForObject("SELECT name FROM lab_items WHERE id=?",String.class,1));
    }
    public Map<String,Object> poolWait()throws Exception{
        try(Connection first=pool.getConnection();Connection second=pool.getConnection()){
            Future<Long> wait=tasks.submit(()->{long start=System.nanoTime();try(Connection third=pool.getConnection()){return System.nanoTime()-start;}});
            Thread.sleep(180);first.close();second.close();return Map.of("waitMillis",wait.get(3,TimeUnit.SECONDS)/1_000_000.0);
        }
    }
    public Map<String,Object> redis(){String key="lab:"+UUID.randomUUID();redis.set(key,"fixture-value");String value=redis.get(key);redis.del(key);return Map.of("value",value,"ping",redis.ping());}
    public Map<String,Object> rabbit()throws Exception{
        ConnectionFactory factory=new ConnectionFactory();factory.setHost(rabbitHost);factory.setPort(rabbitPort);factory.setUsername("lab");factory.setPassword("lab-only");factory.setAutomaticRecoveryEnabled(false);
        try(com.rabbitmq.client.Connection connection=factory.newConnection();Channel channel=connection.createChannel()){
            String queue=channel.queueDeclare("",false,true,true,null).getQueue();byte[] body="private-fixture-message".getBytes(StandardCharsets.UTF_8);channel.confirmSelect();
            channel.basicPublish("",queue,null,body);boolean confirmed=channel.waitForConfirms(5000);GetResponse received=channel.basicGet(queue,true);
            return Map.of("confirmed",confirmed,"bodyPreserved",received!=null&&Arrays.equals(body,received.getBody()));
        }
    }
    public Map<String,Object> apache()throws Exception{
        var request=new org.apache.hc.client5.http.classic.methods.HttpGet(downstream+"/chain");
        var config=org.apache.hc.client5.http.config.RequestConfig.custom().setResponseTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(3)).build();request.setConfig(config);
        List<String> parents=new ArrayList<>();
        for(int i=0;i<2;i++)try(var reply=apache.execute(request)){
            Map<?,?> result=json.readValue(org.apache.hc.core5.http.io.entity.EntityUtils.toString(reply.getEntity()),Map.class);parents.add(result.get("incomingTraceparent").toString());
            if(request.getConfig()!=config||request.containsHeader("traceparent"))throw new IllegalStateException("Request identity/headers changed");
        }
        return Map.of("headersClean",!request.containsHeader("traceparent"),"freshParents",!parents.get(0).equals(parents.get(1)),"propagated",parents.stream().allMatch(s->s.startsWith("00-")));
    }
    public long cpu(){long value=1;for(int i=0;i<800_000;i++)value=(value*1664525+1013904223)^(value>>>11);return value;}
    public synchronized Map<String,Object> memory(){if(retained.size()<8)retained.add(new byte[1024*1024]);return Map.of("retainedMiB",retained.size());}
    @PreDestroy public void close()throws Exception{tasks.shutdownNow();redis.close();apache.close();}
}
