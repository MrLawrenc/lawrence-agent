package agentfixtures.isolated;

/** Loaded by a parent-null class loader in the real JVM Agent integration test. */
public final class IsolatedBusiness {

    private IsolatedBusiness() { }

    public static void block(java.util.concurrent.CountDownLatch entered,
                             java.util.concurrent.CountDownLatch release) throws InterruptedException {
        entered.countDown();
        release.await();
    }

    public static String execute(String value) {
        return "processed:" + value;
    }
}
