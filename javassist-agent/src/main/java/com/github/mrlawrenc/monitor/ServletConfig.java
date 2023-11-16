package com.github.mrlawrenc.monitor;

import lombok.Data;

import java.util.List;

/**
 * @author : Lawrence
 * date  2023/11/10 17:07
 */
@Data
public class ServletConfig {
    private boolean enable;
    private String version;
    private List<String> scanPackages;
}