package com.lawrence.utils.log;

import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class SpringLikeFormatter extends Formatter {

    private static final String PID = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    static {
        DATE_FORMAT.setTimeZone(TimeZone.getDefault());
    }

    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();

        // 时间
        sb.append(DATE_FORMAT.format(new Date(record.getMillis())));
        sb.append("  ");

        // 日志级别，固定 5 个字符宽
        String level = record.getLevel().getName();
        sb.append(String.format("%-5s", level.toUpperCase()));
        sb.append(" ");

        // PID
        sb.append(PID);

        // 线程名
        sb.append("[").append(Thread.currentThread().getName()).append("] ");


        // Logger 名，可缩写包名
        sb.append(record.getLoggerName());
        sb.append("    : ");

        // 日志消息
        sb.append(formatMessage(record));
        sb.append(System.lineSeparator());

        return sb.toString();
    }
}
