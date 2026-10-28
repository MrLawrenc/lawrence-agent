package agentfixtures.rolling;
public final class TargetLoop {
    public static void main(String[] args)throws Exception {
        var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));System.out.println("READY");System.out.flush();
        for(String line;(line=input.readLine())!=null;){if(line.equals("EXIT"))return;if(line.equals("RUN")){root();System.out.println("WORK_DONE");System.out.flush();}}
    }
    public static void root()throws Exception {child();Thread.sleep(1000);child();}
    public static void child()throws Exception {Thread.sleep(50);}
}
