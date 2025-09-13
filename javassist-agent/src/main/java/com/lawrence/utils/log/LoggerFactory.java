package com.lawrence.utils.log;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;

/**
 * 日志工厂，生成 SimpleLog
 */
public class LoggerFactory {

    private static final Map<String, Logger> CACHE = new ConcurrentHashMap<>();
    private static boolean initialized = false;
    private static Level defaultLevel = Level.INFO;

    /**
     * 初始化全局日志等级，只调用一次即可
     */
    public static void init(Level level) {
        defaultLevel = level;

        if (!initialized) {
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger("com.lawrence"); // 只针对自己包
            logger.setUseParentHandlers(false);               // 不向上冒泡
            logger.setLevel(defaultLevel);                    // 设置自己包的等级


            ConsoleHandler handler = new ConsoleHandler();
            handler.setFormatter(new SpringLikeFormatter());
            handler.setLevel(defaultLevel);

            logger.setUseParentHandlers(false);
            logger.setLevel(defaultLevel);
            logger.addHandler(handler);

            initialized = true;
        }
    }

    public static Logger getLogger(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz.getName(), n -> new SimpleLog(java.util.logging.Logger.getLogger(n)));
    }

    public static Level parseLevel(String level) {
        if (level == null) return Level.INFO;
        switch (level.toLowerCase()) {
            case "severe":
                return Level.SEVERE;
            case "warning":
                return Level.WARNING;
            case "info":
                return Level.INFO;
            case "config":
                return Level.CONFIG;
            case "fine":
                return Level.FINE;
            case "debug":
                return Level.FINE;
            case "finer":
                return Level.FINER;
            case "finest":
                return Level.FINEST;
            default:
                return Level.INFO;
        }
    }
}
