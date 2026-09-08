package com.agentmonitor.model.config;

/** Shared package-only glob matcher used by both the selection UI and Agent instrumentation. */
public final class PackagePatternMatcher {

    private PackagePatternMatcher() { }

    public static boolean hasWildcard(String pattern) {
        return pattern != null && pattern.indexOf('*') >= 0;
    }

    /** Matches a Java package path only; callers must never pass a simple class name here. */
    public static boolean matches(String pattern, String packageName) {
        if (pattern == null || pattern.isBlank() || packageName == null || packageName.isBlank()) return false;
        if (!hasWildcard(pattern)) {
            return packageName.equals(pattern) || packageName.startsWith(pattern + '.');
        }
        String regex = toRegex(pattern);
        // A trailing .** denotes a package segment and its descendants.  Test
        // packageName + '.' as well so classes declared directly in a package
        // such as com.example.model are not missed.
        return packageName.matches(regex) || (packageName + '.').matches(regex);
    }

    /** Converts a package glob (for example {@code **.model.**}) to an anchored Java regex. */
    public static String toRegex(String glob) {
        String value = glob == null ? "" : glob.trim();
        StringBuilder regex = new StringBuilder(value.length() * 2 + 2).append('^');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '*') regex.append(".*");
            else if ("\\.^$|?+()[]{}".indexOf(character) >= 0) regex.append('\\').append(character);
            else regex.append(character);
        }
        return regex.append('$').toString();
    }

    /** Matches a full class name by first removing its final simple-class-name segment. */
    public static boolean matchesClassName(String packagePattern, String className) {
        if (className == null) return false;
        int separator = className.lastIndexOf('.');
        return separator > 0 && matches(packagePattern, className.substring(0, separator));
    }
}
