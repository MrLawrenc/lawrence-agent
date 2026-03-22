package com.lawrence.monitor.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具类：全局唯一 ID 生成。
 */
public class StatisticsHelper {
    public static final String TABS = "\t";
    public static final String EMPTY_STR = "";

    private static final AtomicInteger SEQ = new AtomicInteger(Integer.MIN_VALUE);

    private StatisticsHelper() {
    }

    /**
     * 获取全局唯一 ID
     */
    public static String getId() {
        return SEQ.incrementAndGet() + "#" + System.currentTimeMillis();
    }
}