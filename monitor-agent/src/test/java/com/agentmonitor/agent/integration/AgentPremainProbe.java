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
        }
    }
}
