package com.agentmonitor.app.ui;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.config.PackagePatternMatcher;
import java.util.List;
import java.util.regex.Pattern;
public final class ClassSelectionRules {
    private ClassSelectionRules() { }
    public record ClassRulePreset(String label, List<String> conditions) { }
    public record MethodRulePreset(String label, List<String> patterns) { }
    public static List<String> defaultExcludedMethodPatterns() { return MonitoringConfig.Scope.DEFAULT_EXCLUDE_METHODS; }
    public static List<ClassRulePreset> classRulePresets() {
        return List.of(new ClassRulePreset("数据传输类", List.of("cls:*DTO", "cls:*VO")),
                new ClassRulePreset("实体类", List.of("cls:*Entity")), new ClassRulePreset("配置类", List.of("cls:*Config")));
    }
    public static List<MethodRulePreset> methodRulePresets() {
        return List.of(new MethodRulePreset("访问器", List.of("get*", "is*", "set*")),
                new MethodRulePreset("对象方法", List.of("toString", "hashCode", "equals")));
    }
    public static String wildcardToRegex(String value) {
        return "^" + Pattern.quote(value == null ? "" : value).replace("*", "\\E.*\\Q") + "$";
    }
    public static String normalizeClassExcludeCondition(String value) {
        if (value == null || value.isBlank() || value.startsWith("#")) return "";
        String rule = value.trim();
        if (!rule.startsWith("cls:") && !rule.startsWith("pkg:")) rule = "cls:" + rule;
        if (rule.substring(4).isBlank()) throw new IllegalArgumentException("排除规则不能为空: " + rule);
        return rule;
    }
    public static boolean matchesClassExclude(String condition, String fullName, String simpleName, String packageName) {
        if (condition == null) return false;
        if (condition.startsWith("pkg:")) return PackagePatternMatcher.matches(condition.substring(4), packageName);
        if (!condition.startsWith("cls:")) return false;
        String pattern = condition.substring(4);
        return fullName.matches(wildcardToRegex(pattern));
    }
}
