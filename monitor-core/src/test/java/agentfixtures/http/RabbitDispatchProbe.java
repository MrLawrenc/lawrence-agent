package agentfixtures.http;

import com.rabbitmq.client.impl.*;
import java.net.InetAddress;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Rabbit client code with a bounded frame sink; no broker-confirm claim is made. */
final class RabbitDispatchProbe {
    static String publish()throws Exception{
        AtomicInteger frames=new AtomicInteger();
        FrameHandler sink=(FrameHandler)Proxy.newProxyInstance(FrameHandler.class.getClassLoader(),new Class<?>[]{FrameHandler.class},(p,m,a)->{
            if(m.getName().equals("writeFrame")){frames.incrementAndGet();return null;}
            if(m.getName().equals("getAddress")||m.getName().equals("getLocalAddress"))return InetAddress.getLoopbackAddress();
            if(m.getReturnType()==int.class)return 5672;if(m.getReturnType()==boolean.class)return false;return null;
        });
        ConnectionParams params=new ConnectionParams();params.setClientProperties(Map.of());params.setThreadFactory(Executors.defaultThreadFactory());
        AMQConnection connection=new AMQConnection(params,sink);ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            var initialize=AMQConnection.class.getDeclaredMethod("initializeHeartbeatSender");initialize.setAccessible(true);initialize.invoke(connection);
            ConsumerWorkService work=new ConsumerWorkService(executor,Executors.defaultThreadFactory(),0,1000);
            ChannelN channel=new ChannelN(connection,1,work);byte[] body="do-not-capture-message-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            channel.basicPublish("","private-route",null,body);
            return frames.get()>=3?"PUBLISH_OK":"NO_FRAMES";
        }finally{executor.shutdownNow();}
    }
}
