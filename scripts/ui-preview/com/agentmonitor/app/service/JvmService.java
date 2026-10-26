package com.agentmonitor.app.service;

import com.agentmonitor.app.model.JvmProcess;
import java.util.List;
import java.util.Map;

/** Preview-only fixtures. Never enumerates, attaches to, or modifies a real JVM. */
public final class JvmService {
    public static List<JvmProcess> listProcesses() {
        return List.of(new JvmProcess("28416", "com.example.orders.OrderApplication  |  示例订单服务"),
                new JvmProcess("28572", "com.example.billing.BillingApplication  |  示例结算服务"),
                new JvmProcess("29018", "org.gradle.launcher.daemon.bootstrap.GradleDaemon  |  Gradle 8.6"));
    }
    public static List<String> listLoadedClasses(String pid) {
        return List.of("com.example.orders.OrderService", "com.example.orders.OrderController",
                "com.example.orders.OrderRepository", "com.example.orders.OrderDTO",
                "com.example.billing.BillingService", "com.example.billing.PaymentGateway");
    }
    public static Map<String, List<String>> listClassMethods(String pid) {
        return Map.of("com.example.orders.OrderService", List.of("createOrder", "validateOrder", "getOrder"),
                "com.example.orders.OrderController", List.of("create", "list"),
                "com.example.orders.OrderRepository", List.of("save", "findById"));
    }
}
