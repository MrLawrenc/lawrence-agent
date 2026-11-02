package agentfixtures.soak;

/** Isolated target for the desktop soak; no external services or user JVMs. */
public final class SoakTarget {
    public static final class Business {
        private long requests;
        public long request() throws InterruptedException { database(); return ++requests; }
        public void database() throws InterruptedException { Thread.sleep(4); }
    }
    public static void main(String[] args) throws Exception {
        Business work=new Business();
        System.out.println("READY "+ProcessHandle.current().pid());System.out.flush();
        boolean quiesced=false;java.nio.file.Path marker=java.nio.file.Path.of(args[0],"quiesce.marker");
        while(!Thread.currentThread().isInterrupted()){
            if(java.nio.file.Files.exists(marker)){if(!quiesced){System.out.println("QUIESCED");System.out.flush();quiesced=true;}Thread.sleep(100);continue;}
            work.request();Thread.sleep(96);
        }
    }
}
