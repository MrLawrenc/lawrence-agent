package com.lawrence;

/**
 * @author : Lawrence
 * date  2023/11/10 17:20
 */
public class Log {
    private final static String A = "\\{}";

    private Log() {
    }

    public static void info(String formatMsg, Object... args) {
        String msg = formatMsg.replaceAll(A, "%s") + "%n";
        System.out.printf(msg, args);
    }
}