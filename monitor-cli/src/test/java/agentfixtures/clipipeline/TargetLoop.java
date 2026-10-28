package agentfixtures.clipipeline;
import java.io.*;
public final class TargetLoop {
    public static void main(String[] args) throws Exception {
        System.out.println("READY"); System.out.flush();
        var input = new BufferedReader(new InputStreamReader(System.in));
        for (String line; (line = input.readLine()) != null;) {
            if (line.equals("EXIT")) break;
            if (line.equals("RUN")) {
                for (int i = 0; i < 3; i++) work();
                System.out.println("WORK_DONE"); System.out.flush();
            }
        }
    }
    public static void work() throws InterruptedException { Thread.sleep(5); }
}
