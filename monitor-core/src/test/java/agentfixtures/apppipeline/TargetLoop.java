package agentfixtures.apppipeline;
public final class TargetLoop {
    public static void main(String[] args) throws Exception {
        System.out.println("READY");
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        for (String command; (command = reader.readLine()) != null;) {
            if (command.equals("RUN")) { for (int i = 0; i < 3; i++) work(); System.out.println("WORK_DONE"); }
            else if (command.equals("EXIT")) break;
        }
    }
    public static String work() throws Exception { Thread.sleep(5); return "collected"; }
}
