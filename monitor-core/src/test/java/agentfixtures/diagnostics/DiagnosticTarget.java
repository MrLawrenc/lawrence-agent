package agentfixtures.diagnostics;
import java.util.concurrent.*;
import java.nio.file.*;
import java.sql.*;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.util.logging.Logger;
public class DiagnosticTarget {
    private static final ExecutorService POOL=Executors.newFixedThreadPool(2);
    public static void main(String[] args)throws Exception{
        deadlockFixture();
        System.out.println("READY");System.out.flush();
        while(!Files.exists(Path.of(args[0])))Thread.sleep(50);
        for(int i=0;i<8;i++){business();Thread.sleep(80);}
        for(int i=0;i<200;i++){try{throwError();}catch(IllegalStateException ignored){}}
        System.gc();
        new Thread(DiagnosticTarget::stuck,"unfinished-business").start();
        System.out.println("WORK_DONE");System.out.flush();Thread.sleep(60000);
    }
    private static void deadlockFixture(){Object a=new Object(),b=new Object();CountDownLatch entered=new CountDownLatch(2);for(int i=0;i<2;i++){final int n=i;Thread thread=new Thread(()->{synchronized(n==0?a:b){entered.countDown();try{entered.await();}catch(InterruptedException ignored){}synchronized(n==0?b:a){}}},"deadlock-"+i);thread.setDaemon(true);thread.start();}}
    public static void business()throws Exception{
        POOL.submit(DiagnosticTarget::leaf).get();
        CompletableFuture.runAsync(DiagnosticTarget::leaf).join();
        new FakeDataSource().getConnection();
        ThreadPoolExecutor rejected=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),new ThreadPoolExecutor.DiscardPolicy());rejected.shutdown();rejected.execute(DiagnosticTarget::leaf);
        try{throwError();}catch(IllegalStateException ignored){}
    }
    public static void leaf(){long end=System.nanoTime()+2_000_000;while(System.nanoTime()<end){double x=Math.sqrt(System.nanoTime());}byte[] allocation=new byte[10000];allocation[0]=1;}
    public static void throwError(){throw new IllegalStateException("diagnostic fixture");}
    public static void stuck(){try{Thread.sleep(60000);}catch(InterruptedException ignored){}}
    public static class FakeDataSource implements DataSource{
        public Connection getConnection(){try{Thread.sleep(5);}catch(InterruptedException ignored){}return null;}
        public Connection getConnection(String u,String p){return getConnection();}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter p){}public void setLoginTimeout(int t){}public int getLoginTimeout(){return 0;}public Logger getParentLogger(){return Logger.getGlobal();}
        public <T>T unwrap(Class<T> t)throws SQLException{throw new SQLException();}public boolean isWrapperFor(Class<?> t){return false;}
    }
}
