package com.lawrence.monitor;

import lombok.Data;

import java.util.List;

@Data
public class MethodMatchRule {

    /** 方法名（支持 * 通配） */
    private List<String> methodNames;

    /** 是否监控静态方法（可覆盖类级设置） */
    private Boolean includeStatic;

    /** 是否监控构造方法（可覆盖类级设置） */
    private Boolean includeConstructor;

    // getter / setter
}
