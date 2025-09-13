package com.lawrence.utils.log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class SimpleSlf4jFormatter extends Formatter {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append(DATE_FORMAT.format(new Date(record.getMillis())));
        sb.append(" ");
        sb.append(record.getLevel().getName().toLowerCase());
        sb.append(" ");
        sb.append(record.getLoggerName());
        sb.append(" - ");
        sb.append(formatMessage(record));
        sb.append(System.lineSeparator());
        return sb.toString();
    }
}
