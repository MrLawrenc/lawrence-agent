package com.lawrence.monitor;

import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author : Lawrence
 * date  2023/11/10 17:06
 */
@Data
public class AgentConfig {
    private JdbcConfig jdbcConfig;
    private ServletConfig servletConfig;

    static AgentConfig init(Properties properties) {
        AgentConfig agentConfig = new AgentConfig();
        JdbcConfig jdbcConfig = new JdbcConfig();
        ServletConfig servletConfig = new ServletConfig();
        agentConfig.setJdbcConfig(jdbcConfig);
        agentConfig.setServletConfig(servletConfig);

        boolean enable = Boolean.parseBoolean(properties.getOrDefault("jdbc.enable", Boolean.FALSE.toString()).toString());
        String scanPackages = properties.getOrDefault("jdbc.scan-packages", "").toString();
        jdbcConfig.setEnable(enable);
        if (Objects.nonNull(scanPackages) && !scanPackages.trim().equals("")) {
            List<String> packages = Arrays.stream(scanPackages.split(",")).collect(Collectors.toList());
            jdbcConfig.setScanPackages(packages);
        }

        boolean servletEnable = Boolean.parseBoolean(properties.getOrDefault("servlet.enable", Boolean.FALSE.toString()).toString());

        String version = properties.getOrDefault("servlet.version", "8").toString();
        String servletScanPackages = properties.getOrDefault("servlet.scan-packages", "").toString();
        servletConfig.setEnable(servletEnable);
        servletConfig.setVersion(version);
        if (Objects.nonNull(servletScanPackages) && !servletScanPackages.trim().equals("")) {
            List<String> packages = Arrays.stream(scanPackages.split(",")).collect(Collectors.toList());
            servletConfig.setScanPackages(packages);
        }

        return agentConfig;
    }


}