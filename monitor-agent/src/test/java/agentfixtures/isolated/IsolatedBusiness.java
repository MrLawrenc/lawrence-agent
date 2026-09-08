package agentfixtures.isolated;

/** Loaded by a parent-null class loader in the real JVM Agent integration test. */
public final class IsolatedBusiness {

    private IsolatedBusiness() { }

    public static String execute(String value) {
        return "processed:" + value;
    }
}
