package com.lawrence.monitor;

import lombok.Data;

import java.util.List;

/**
 * @author : Lawrence
 * date  2023/11/10 17:07
 */
@Data
public class JdbcConfig {
    private boolean enable;
    private List<String> scanPackages;
}