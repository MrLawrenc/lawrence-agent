package com.lawrence.monitor;

import lombok.Data;

import java.util.List;
import java.util.Objects;

@Data
public class ClassMatchRule {

    /**
     * 全限定类名（支持通配符 * 或 **）
     */
    private String className;

    /**
     * 方法匹配规则
     */
    private List<MethodMatchRule> methods;


    private String regex;

    public boolean match(String sourceClassName) {
        if (this.className == null || sourceClassName == null) {
            return false;
        }
        if (Objects.isNull(regex)) {
            this.regex = toRegex(this.className);
        }
        return sourceClassName.matches(this.regex);
    }

    /**
     * 规则：
     * **  -> 任意包层级
     * *   -> 单层包或类名
     */
    static String toRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append("^");

        char[] chars = pattern.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];

            // ** -> 任意包
            if (c == '*' && i + 1 < chars.length && chars[i + 1] == '*') {
                sb.append(".*");
                i++;
                continue;
            }

            // * -> 单层（不跨 .）
            if (c == '*') {
                sb.append("[^\\.]*");
                continue;
            }

            // . 要转义
            if (c == '.') {
                sb.append("\\.");
                continue;
            }

            // 其他正则特殊字符
            if ("+()^$|{}[]\\".indexOf(c) >= 0) {
                sb.append("\\");
            }
            sb.append(c);
        }

        sb.append("$");
        return sb.toString();
    }
}
