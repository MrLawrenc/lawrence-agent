package com.lawrence.utils.log;

/**
 * 简单日志接口，类似 SLF4J
 */
public interface Logger {
    void info(String msg, Object... args);

    void debug(String msg, Object... args);

    void warn(String msg, Object... args);

    void error(String msg, Object... args);

    void error(String msg, Throwable t, Object... args);
}
