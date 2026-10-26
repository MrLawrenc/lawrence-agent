package com.agentmonitor.agent.integration;

import java.net.URL;
import java.net.URLClassLoader;

/** Standalone child-JVM target used by {@link AgentClassLoadingIntegrationTest}. */
public final class AgentPremainProbe {

    private AgentPremainProbe() { }

    public static void main(String[] args) throws Exception {
        URL testClasses = AgentPremainProbe.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader applicationLoader = new URLClassLoader(new URL[] { testClasses }, null)) {
            // The bridge must resolve from Bootstrap even for an isolated application loader.
            Class<?> bridge = Class.forName("com.agentmonitor.bootstrap.bridge.BootstrapBridge", false,
                    applicationLoader);
            System.out.println("bridge-loader=" + (bridge.getClassLoader() == null ? "bootstrap" : "unexpected"));

            // The complete Agent core must not be reachable from the target application's loader.
            try {
                Class.forName("com.agentmonitor.agent.AgentMain", false, applicationLoader);
                System.out.println("core-visible");
            } catch (ClassNotFoundException expected) {
                System.out.println("core-hidden");
            }
            try {
                Class.forName("com.agentmonitor.agent.AgentMain", false, ClassLoader.getSystemClassLoader());
                System.out.println("system-core-visible");
            } catch (ClassNotFoundException expected) {
                System.out.println("system-core-hidden");
            }

            // Agent installation runs asynchronously; let the retransformation transformer become ready.
            Thread.sleep(1_500);
            Class<?> business = Class.forName("agentfixtures.isolated.IsolatedBusiness", true, applicationLoader);
            Object value = business.getMethod("execute", String.class).invoke(null, "ok");
            System.out.println("business-result=" + value);
            Thread.sleep(300);
            if (args.length > 0 && "--stop-in-flight".equals(args[0])) {
                var entered = new java.util.concurrent.CountDownLatch(1);
                var release = new java.util.concurrent.CountDownLatch(1);
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread worker = new Thread(() -> {
                    try { business.getMethod("block", java.util.concurrent.CountDownLatch.class,
                            java.util.concurrent.CountDownLatch.class).invoke(null, entered, release); }
                    catch (Throwable error) { failure.set(error); }
                });
                worker.start();
                try {
                    if (!entered.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Business never entered");
                    Object stopped = bridge.getMethod("detachActive").invoke(null);
                    System.out.println("stop-restored=" + stopped.getClass().getMethod("restored").invoke(stopped));
                    System.out.println("stop-output-drained=" + stopped.getClass().getMethod("outputDrained").invoke(stopped));
                } finally { release.countDown(); worker.join(2_000); }
                if (worker.isAlive() || failure.get() != null) throw new AssertionError("Business stop failure", failure.get());
            }
        }
    }
}
