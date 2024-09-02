package com.lawrence.monitor;

import lombok.Data;

import java.util.*;
import java.util.stream.Stream;

/**
 * @author : Lawrence
 * date  2023/11/10 17:06
 */
@Data
public class AgentConfig {
    private JdbcConfig jdbcConfig;
    private ServletConfig servletConfig;

    public static AgentConfig init(Properties properties) {
        AgentConfig agentConfig = new AgentConfig();
        JdbcConfig jdbcConfig = new JdbcConfig();
        ServletConfig servletConfig = new ServletConfig();
        agentConfig.setJdbcConfig(jdbcConfig);
        agentConfig.setServletConfig(servletConfig);

        boolean enable = Boolean.parseBoolean(properties.getOrDefault("jdbc.enable", Boolean.FALSE.toString()).toString());
        String scanPackages = properties.getOrDefault("jdbc.scan-packages", "").toString();
        jdbcConfig.setEnable(enable);
        if (Objects.nonNull(scanPackages) && !scanPackages.trim().isEmpty()) {
            List<String> packages = Stream.of(scanPackages.split(",")).toList();
            jdbcConfig.setScanPackages(packages);
        }

        boolean servletEnable = Boolean.parseBoolean(properties.getOrDefault("servlet.enable", Boolean.FALSE.toString()).toString());

        String version = properties.getOrDefault("servlet.version", "8").toString();
        String servletScanPackages = properties.getOrDefault("servlet.scan-packages", "").toString();
        servletConfig.setEnable(servletEnable);
        servletConfig.setVersion(version);
        if (Objects.nonNull(servletScanPackages) && !servletScanPackages.trim().isEmpty()) {
            List<String> packages = Stream.of(scanPackages.split(",")).toList();
            servletConfig.setScanPackages(packages);
        }

        return agentConfig;
    }


}