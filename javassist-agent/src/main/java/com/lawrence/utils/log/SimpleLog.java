package com.lawrence.utils.log;


import java.util.Arrays;
import java.util.IllegalFormatException;
import java.util.logging.Level;

/**
 * 基于 java.util.logging 的轻量 Log 实现
 */
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

    // error 方法，支持可变参数和异常
    public void error(String msg, Throwable t, Object... args) {
        String formattedMsg;
        if (args == null || args.length == 0) {
            formattedMsg = msg;
        } else {
            try {
                formattedMsg = String.format(msg, args);
            } catch (IllegalFormatException e) {
                formattedMsg = msg + " | args=" + Arrays.toString(args);
            }
        }
        //todo
        logger.severe(formattedMsg); // 打消息
        if (t != null) {
            t.printStackTrace(System.err); // 打堆栈
        }
    }


    /**
     * 占位符 {} 替换
     */
    private String format(String msg, Object... args) {
        if (msg == null || args == null || args.length == 0) return msg;
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int lastIndex = 0;
        for (int i = 0; i < msg.length() - 1; i++) {
            if (msg.charAt(i) == '{' && msg.charAt(i + 1) == '}') {
                sb.append(msg, lastIndex, i);
                sb.append(argIndex < args.length ? args[argIndex++] : "{}");
                i++; // 跳过 }
                lastIndex = i + 1;
            }
        }
        sb.append(msg.substring(lastIndex));
        return sb.toString();
    }

}
