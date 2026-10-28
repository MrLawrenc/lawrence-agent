package com.agentmonitor.core.service;

import com.agentmonitor.model.jvm.JvmProcess;
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
        if (pid.equals("scale-preview")) {
            var names=new java.util.ArrayList<String>(141307);
            for(int i=0;i<2250;i++) names.add(String.format(java.util.Locale.ROOT,"com.example.orders.group%03d.OrderService%05d",i/35,i));
            for(int i=2250;i<141307;i++) names.add(String.format(java.util.Locale.ROOT,"org.library.module%04d.LibraryClass%06d",i/40,i));
            return names;
        }
        if (pid.equals("large-preview")) {
            try {Thread.sleep(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            return java.util.stream.IntStream.range(0,400).mapToObj(i -> "example.many.Item"+String.format("%03d",i)).toList();
        }
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
