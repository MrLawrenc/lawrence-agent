package com.agentmonitor.model.config;

import java.util.*;
import java.util.regex.Pattern;

/** The selection contract is independent of browsing, search, loaded classes, and JavaFX. */
public final class ScopeSelection {
    public enum Change { APPLIED, EXCLUDED_BY_RULE }
    private final LinkedHashSet<String> packages, classes, exclusions, methods;
    private record MethodRule(boolean qualified,Pattern pattern){}
    private List<MethodRule> methodRules=List.of();
    private final Map<String, Pattern> patterns = new HashMap<>();
    public ScopeSelection(MonitoringConfig.Scope scope) {
        packages = new LinkedHashSet<>(scope.includePackages()); classes = new LinkedHashSet<>(scope.includeClasses());
        exclusions = new LinkedHashSet<>(scope.excludeConditions()); methods = new LinkedHashSet<>(scope.excludeMethods());compileMethods();
    }
    public MonitoringConfig.Scope scope() { return new MonitoringConfig.Scope(List.copyOf(packages), List.copyOf(classes), List.copyOf(exclusions), List.copyOf(methods)); }
    public ScopeSelection copy() { return new ScopeSelection(scope()); }
    public boolean covered(String className) {
        if(classes.contains(className))return true;
        String pkg=packageOf(className);for(String prefix:packages)if(includesPackage(prefix,pkg))return true;return false;
    }
    public String exclusion(String className) {
        for (String condition : exclusions) {
            if (condition.startsWith("pkg:") && packageMatches(condition.substring(4), packageOf(className))) return condition;
            if (condition.startsWith("cls:") && wildcard(condition.substring(4), className)) return condition;
        }
        return "";
    }
    public boolean selected(String className) { return covered(className) && exclusion(className).isEmpty(); }
    public Change selectClass(String className, boolean selected) {
        if (selected) {
            String exact = "cls:" + className;
            LinkedHashSet<String> previous = new LinkedHashSet<>(exclusions); exclusions.remove(exact);
            if (!exclusion(className).isEmpty()) { exclusions.clear(); exclusions.addAll(previous); return Change.EXCLUDED_BY_RULE; }
            if (!covered(className)) classes.add(className);
        } else {
            classes.remove(className);
            if (packages.stream().anyMatch(p -> includesPackage(p, packageOf(className)))) exclusions.add("cls:" + className);
        }
        return Change.APPLIED;
    }
    public Change selectPackage(String name, boolean selected) {
        if (name == null || name.isBlank()) return Change.APPLIED;
        if (selected) {
            if (exclusions.stream().anyMatch(e -> e.startsWith("pkg:") && packageMatches(e.substring(4), name)
                    && !e.equals("pkg:" + name))) return Change.EXCLUDED_BY_RULE;
            // Re-enable only explicit package exclusions; never silently rewrite broad user rules.
            exclusions.removeIf(e -> e.startsWith("pkg:") && !PackagePatternMatcher.hasWildcard(e.substring(4)) && under(e.substring(4), name));
            packages.removeIf(p -> !PackagePatternMatcher.hasWildcard(p) && under(p, name));
            if (packages.stream().noneMatch(p -> includesPackage(p, name))) packages.add(name);
        } else {
            packages.removeIf(p -> !PackagePatternMatcher.hasWildcard(p) && under(p, name));
            classes.removeIf(c -> under(packageOf(c), name));
            if (packages.stream().anyMatch(p -> includesPackage(p, name))) exclusions.add("pkg:" + name);
        }
        return Change.APPLIED;
    }
    public boolean packageIncluded(String name) {
        return packages.stream().anyMatch(p -> includesPackage(p, name))
                && exclusions.stream().noneMatch(e -> e.startsWith("pkg:") && packageMatches(e.substring(4), name));
    }
    public void excludePackage(String name) {
        if(name==null||name.isBlank())return;
        selectPackage(name,false);
        exclusions.add("pkg:"+name);
    }
    /** Remove only this package's explicit rules; preserve child and independent-class rules. */
    public void inheritPackage(String name) {
        packages.remove(name);exclusions.remove("pkg:"+name);
    }
    public String packageExclusion(String name) {
        return exclusions.stream().filter(e->e.startsWith("pkg:")&&packageMatches(e.substring(4),name)).findFirst().orElse("");
    }
    public boolean packagePartial(String name) {
        return packageIncluded(name)
                ? exclusions.stream().anyMatch(e -> e.startsWith("pkg:") && under(e.substring(4), name))
                : packages.stream().anyMatch(p -> under(p, name)) || classes.stream().anyMatch(c -> under(packageOf(c), name));
    }
    public boolean methodSelected(String className, String method) {
        for (MethodRule rule:methodRules) if(rule.pattern.matcher(rule.qualified ? className+"."+method : method).matches())return false;
        return true;
    }
    private void compileMethods() {
        List<MethodRule> compiled=new ArrayList<>();
        for(String value:methods) {
            boolean alias=value.matches("[A-Za-z_$][\\w$]*\\.\\*");
            String glob=alias ? value.substring(0,value.length()-2)+"*" : value;
            compiled.add(new MethodRule(!alias && value.contains("."),Pattern.compile(PackagePatternMatcher.toRegex(glob))));
        }
        methodRules=List.copyOf(compiled);
    }
    public void clearIncludes() { packages.clear(); classes.clear(); }
    public void replaceRules(List<String> conditions, List<String> methodPatterns) {
        exclusions.clear(); exclusions.addAll(conditions); methods.clear(); methods.addAll(methodPatterns);compileMethods();
    }
    private boolean includesPackage(String prefix, String name) {
        while (prefix.endsWith(".")) prefix = prefix.substring(0,prefix.length()-1);
        return !prefix.isEmpty() && under(name,prefix);
    }
    private boolean packageMatches(String p, String name) {
        if (!PackagePatternMatcher.hasWildcard(p)) return includesPackage(p,name);
        return wildcard(p, name) || wildcard(p, name + ".");
    }
    private boolean wildcard(String glob, String target) {
        return patterns.computeIfAbsent(glob, p -> Pattern.compile(PackagePatternMatcher.toRegex(p))).matcher(target).matches();
    }
    public static String packageOf(String cls) { int dot = cls.lastIndexOf('.'); return dot < 0 ? "" : cls.substring(0, dot); }
    public static boolean under(String name, String parent) { return name.equals(parent) || name.startsWith(parent + "."); }
}
