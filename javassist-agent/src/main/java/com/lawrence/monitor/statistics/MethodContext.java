package com.lawrence.monitor.statistics;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class MethodContext {
    /** 方法所在类全名 */
    private String className;

    /** 方法名 */
    private String methodName;

    /** 方法签名 */
    private String methodDesc;

    /** 方法执行者实例，静态方法为 null */
    private Object executor;

    /** 方法参数 */
    private Object[] args;

    /** 是否静态方法 */
    private boolean isStatic;

    /** 是否构造方法 */
    private boolean isConstructor;
}
