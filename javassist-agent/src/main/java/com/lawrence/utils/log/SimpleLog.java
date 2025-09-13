package com.lawrence.utils.log;


import java.util.logging.Level;

/** 基于 java.util.logging 的轻量 Log 实现 */
public class SimpleLog implements Logger {

    private final java.util.logging.Logger logger;

    public SimpleLog(java.util.logging.Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String msg, Object... args) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info(format(msg, args));
        }
    }

    @Override
    public void debug(String msg, Object... args) {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(format(msg, args));
        }
    }

    @Override
    public void warn(String msg, Object... args) {
        if (logger.isLoggable(Level.WARNING)) {
            logger.warning(format(msg, args));
        }
    }

    @Override
    public void error(String msg, Object... args) {
        if (logger.isLoggable(Level.SEVERE)) {
            logger.severe(format(msg, args));
        }
    }

    /** 占位符 {} 替换 */
    private String format(String msg, Object... args) {
        if (msg == null || args == null || args.length == 0) return msg;
        for (Object arg : args) {
            msg = msg.replaceFirst("\\{\\}", arg == null ? "null" : arg.toString());
        }
        return msg;
    }
}
