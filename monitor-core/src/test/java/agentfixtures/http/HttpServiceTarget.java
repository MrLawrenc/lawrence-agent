package agentfixtures.http;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.catalina.startup.Tomcat;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;

/** Real Servlet container + real JDK HTTP client in an owned child JVM. */
public final class HttpServiceTarget extends HttpServlet {
    private final String role,downstream;
    private final HttpClient client=HttpClient.newHttpClient();
    private final org.apache.http.impl.client.CloseableHttpClient apache=org.apache.http.impl.client.HttpClients.createDefault();
    HttpServiceTarget(String role,String downstream){this.role=role;this.downstream=downstream;}
    public static void main(String[] args)throws Exception{
        Tomcat tomcat=new Tomcat();tomcat.setBaseDir(args[2]);tomcat.setPort(0);tomcat.getConnector();
        var context=tomcat.addContext("",args[2]);var wrapper=Tomcat.addServlet(context,"work",new HttpServiceTarget(args[0],args[1]));wrapper.setAsyncSupported(true);context.addServletMappingDecoded("/","work");
        tomcat.start();System.out.println("READY "+tomcat.getConnector().getLocalPort());System.out.flush();
        new BufferedReader(new InputStreamReader(System.in)).readLine();tomcat.stop();tomcat.destroy();
    }
    @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException{
        String path=request.getRequestURI();request.setAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern",path.startsWith("/orders/")?"/orders/{id}":path);
        if(path.equals("/async")){
            AsyncContext async=request.startAsync();CompletableFuture.runAsync(()->{try{Thread.sleep(300);async.getResponse().getWriter().print("ASYNC");async.complete();}catch(Exception e){throw new RuntimeException(e);}});return;
        }
        if(path.equals("/slow"))try{Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        if(path.equals("/fail")){response.setStatus(503);response.getWriter().print("FAIL");return;}
        try{
            if(path.equals("/rabbit")){response.getWriter().print(RabbitDispatchProbe.publish());return;}
            if(path.equals("/redis")){
                try(var connection=new redis.clients.jedis.Connection("127.0.0.1",Integer.getInteger("agent.test.redis.port"))){
                    Object pong=connection.executeCommand(redis.clients.jedis.Protocol.Command.PING);response.getWriter().print(pong instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):pong.toString());
                }return;
            }
            if(role.equals("A")&&path.startsWith("/apache")){
                boolean timeout=path.equals("/apacheTimeout"),cancel=path.equals("/apacheCancel");
                var outgoing=new org.apache.http.client.methods.HttpGet(downstream+(timeout||cancel?"/slow":"/work"));
                var config=org.apache.http.client.config.RequestConfig.custom().setSocketTimeout(timeout?30:2000).build();outgoing.setConfig(config);
                if(cancel){
                    var task=CompletableFuture.supplyAsync(()->{try(var reply=apache.execute(outgoing)){return "UNEXPECTED";}catch(Exception e){return outgoing.isAborted()?"CANCEL_OK":"CANCEL_LOST";}});
                    Thread.sleep(50);outgoing.abort();response.getWriter().print(task.get(5,TimeUnit.SECONDS));return;
                }
                try{
                    String previous=null;
                    for(int i=0;i<2;i++)try(var reply=apache.execute(outgoing)){
                        String body=org.apache.http.util.EntityUtils.toString(reply.getEntity());
                        if(!body.contains("B:00-")||body.equals(previous)||outgoing.getFirstHeader("traceparent")!=null||outgoing.getConfig()!=config)throw new IllegalStateException("Apache request semantics changed: "+body);
                        previous=body;
                    }
                    response.getWriter().print(timeout?"TIMEOUT_LOST":"APACHE_OK");
                }catch(java.net.SocketTimeoutException expected){response.getWriter().print("TIMEOUT_OK");}
                return;
            }
            if(role.equals("A")&&path.equals("/clientAsync")){
                client.sendAsync(HttpRequest.newBuilder(URI.create(downstream+"/slow")).build(),HttpResponse.BodyHandlers.ofString());response.setStatus(202);response.getWriter().print("SUBMITTED");return;
            }
            String result=role+":"+request.getHeader("traceparent");
            if(!downstream.equals("-")){
                int count=role.equals("A")?2:1;
                for(int i=0;i<count;i++)result+="|"+client.send(HttpRequest.newBuilder(URI.create(downstream+"/work")).build(),HttpResponse.BodyHandlers.ofString()).body();
            }
            response.getWriter().print(result);
        }catch(Exception failure){response.setStatus(502);response.getWriter().print(failure.toString());}
    }
    @Override protected void doPost(HttpServletRequest request,HttpServletResponse response)throws IOException{
        request.setAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern","/body");byte[] body=request.getInputStream().readAllBytes();
        try{if(!downstream.equals("-")){
            var publisher=HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(body));
            response.getWriter().print(client.send(HttpRequest.newBuilder(URI.create(downstream+"/body")).POST(publisher).build(),HttpResponse.BodyHandlers.ofString()).body());
        }else response.getOutputStream().write(body);}catch(Exception failure){throw new IOException(failure);}
    }
}
