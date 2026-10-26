package com.agentmonitor.app.ui;
import java.util.List;
import java.util.regex.Pattern;
/** Minimal fixture rules for rendering the real selector; not the production rule engine. */
public final class ClassSelectionRules {
    public record ClassRulePreset(String label, List<String> conditions) {}
    public record MethodRulePreset(String label, List<String> patterns) {}
    public static List<String> defaultExcludedMethodPatterns() { return List.of("get*", "set*"); }
    public static List<ClassRulePreset> classRulePresets() {
        return List.of(new ClassRulePreset("数据传输类", List.of("cls:*DTO")));
    }
    public static List<MethodRulePreset> methodRulePresets() {
        return List.of(new MethodRulePreset("访问器", defaultExcludedMethodPatterns()));
    }
    public static String normalizeClassExcludeCondition(String value) { return value; }
    public static String wildcardToRegex(String value) {
        return Pattern.quote(value).replace("*", "\\E.*\\Q");
    }
    public static boolean matchesClassExclude(String rule, String full, String simple, String pkg) {
        if (rule.startsWith("pkg:")) return pkg.startsWith(rule.substring(4));
        String pattern = wildcardToRegex(rule.replaceFirst("^cls:", ""));
        return full.matches(pattern) || simple.matches(pattern);
    }
}
