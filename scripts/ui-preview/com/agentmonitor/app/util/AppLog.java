package com.agentmonitor.app.util;
/** Preview logs stay in the invoking terminal. */
public final class AppLog {
    public static java.nio.file.Path file() { return java.nio.file.Path.of("preview-console"); }
    public static void info(String value) { System.out.println(value); }
    public static void warn(String value) { System.err.println(value); }
    public static void error(String value, Throwable error) { System.err.println(value); error.printStackTrace(); }
}
