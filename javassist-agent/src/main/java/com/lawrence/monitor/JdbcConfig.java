package com.lawrence.monitor;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

/**
 * @author : Lawrence
 * date  2023/11/10 17:07
 */
@Data
public class JdbcConfig {
    private boolean enable;
    private List<String> scanPackages;

    /** 需要监控的 JDBC 驱动类全限定名，默认支持 MySQL 和 H2 */
    private List<String> driverClasses = Arrays.asList(
            "com.mysql.cj.jdbc.NonRegisteringDriver",
            "org.h2.Driver"
    );
}