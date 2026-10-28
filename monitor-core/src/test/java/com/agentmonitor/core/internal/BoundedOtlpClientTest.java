package com.agentmonitor.core.internal;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
class BoundedOtlpClientTest {
    @Test void aPeerThatNeverReadsCannotBlockTheRequestOrClose() throws Exception {
        try(var listener=new ServerSocket(0); var transport=new BoundedOtlpClient()) {
            var held=new CountDownLatch(1);
            var peer=CompletableFuture.runAsync(() -> {try(var socket=listener.accept()){held.await(5,TimeUnit.SECONDS);}catch(Exception ignored){}});
            long started=System.nanoTime();
            try {
                assertThrows(Exception.class,() -> transport.post(URI.create("http://127.0.0.1:"+listener.getLocalPort()+"/v1/traces"),new byte[16*1024*1024]));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<3000,"Whole request deadline includes blocked writes");
            } finally {held.countDown();peer.get(2,TimeUnit.SECONDS);}
        }
    }
}
