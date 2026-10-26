package com.agentmonitor.agent.interceptor;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;

/** Bounded, valid JSON snapshots. Never calls an application's arbitrary toString(). */
public final class ValueCapture {
    private static final int MAX_CHARACTERS = 16_384;
    private static final int MAX_STRING = 2_048;
    private static final int MAX_ITEMS = 20;
    private static final ThreadLocal<Boolean> CAPTURING = new ThreadLocal<>();

    private ValueCapture() { }
    public record Result(String text, boolean truncated) { }
    public static boolean active() { return Boolean.TRUE.equals(CAPTURING.get()); }

    public static <T> T suppress(java.util.function.Supplier<T> action) {
        boolean outer = active();
        CAPTURING.set(true);
        try { return action.get(); }
        finally { if (!outer) CAPTURING.remove(); }
    }

    public static Result arguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) return new Result("", false);
        return capture(arguments.length == 1 ? arguments[0] : arguments);
    }

    public static Result capture(Object value) {
        if (active()) return new Result("{\"_captureSkipped\":true}", true);
        CAPTURING.set(true);
        Writer writer = new Writer();
        try {
            writer.value(value, 2);
            return new Result(writer.json.toString(), writer.truncated);
        } catch (LimitReached ignored) {
            return new Result("{\"_truncated\":true,\"_reason\":\"capture_budget\"}", true);
        } catch (Throwable error) {
            return new Result("{\"_serializeError\":true}", true);
        } finally {
            CAPTURING.remove();
        }
    }

    private static final class LimitReached extends RuntimeException {
        private LimitReached() { super(null, null, false, false); }
    }
    private static final class Writer {
        private final StringBuilder json = new StringBuilder();
        private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        private boolean truncated;
        private void append(String text) {
            if (text.length() > MAX_CHARACTERS - json.length()) throw new LimitReached();
            json.append(text);
        }
        private void string(String text) {
            append("\"");
            int end = Math.min(text.length(), MAX_STRING);
            if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            for (int i = 0; i < end; i++) {
                char c = text.charAt(i);
                append(switch (c) {
                    case '"' -> "\\\"";
                    case '\\' -> "\\\\";
                    case '\n' -> "\\n";
                    case '\r' -> "\\r";
                    case '\t' -> "\\t";
                    default -> c < 0x20 ? String.format("\\u%04x", (int)c) : String.valueOf(c);
                });
            }
            if (end < text.length()) { truncated = true; append("…"); }
            append("\"");
        }
        private void summary(Object value) {
            truncated = true;
            append("{\"_type\":"); string(value.getClass().getName()); append(",\"_truncated\":true}");
        }
        private String mapKey(Object key) {
            if (key == null) return "null";
            if (key instanceof String text) return text;
            if (key instanceof Character || key instanceof Boolean || key instanceof Byte
                    || key instanceof Short || key instanceof Integer || key instanceof Long
                    || key instanceof Float || key instanceof Double) return key.toString();
            if (key instanceof Enum<?> value) return value.name();
            truncated = true;
            return key.getClass().getName();
        }
        private void value(Object value, int depth) throws IllegalAccessException {
            if (value == null) { append("null"); return; }
            if (value instanceof String text) { string(text); return; }
            if (value instanceof Character c) { string(c.toString()); return; }
            if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
                String numeric = value.toString();
                if (numeric.length() > MAX_STRING) { summary(value); return; }
                append(numeric); return;
            }
            if (value instanceof Double || value instanceof Float) {
                double number = ((Number)value).doubleValue();
                if (Double.isFinite(number)) append(value.toString()); else string(value.toString());
                return;
            }
            if (depth < 0 || visited.put(value, Boolean.TRUE) != null) { summary(value); return; }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                append("[");
                for (int i = 0; i < Math.min(length, MAX_ITEMS); i++) {
                    if (i > 0) append(","); value(Array.get(value, i), depth - 1);
                }
                if (length > MAX_ITEMS) { truncated = true; append(", {\"_truncated\":true}"); }
                append("]");
            } else if (value instanceof Map<?, ?> map) {
                append("{"); int count = 0;
                for (var entry : map.entrySet()) {
                    if (count == MAX_ITEMS) { truncated = true; break; }
                    if (count++ > 0) append(",");
                    Object key = entry.getKey();
                    string(mapKey(key));
                    append(":"); value(entry.getValue(), depth - 1);
                }
                append("}");
            } else if (value instanceof Iterable<?> iterable && !(value instanceof java.util.Queue<?>)) {
                append("["); int count = 0;
                for (Object item : iterable) {
                    if (count == MAX_ITEMS) { truncated = true; break; }
                    if (count++ > 0) append(","); value(item, depth - 1);
                }
                append("]");
            } else if (value instanceof java.util.Queue<?>) {
                summary(value);
            } else {
                append("{"); int count = 0;
                outer: for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                    for (var field : type.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                        if (!field.trySetAccessible()) { truncated = true; continue; }
                        if (count == MAX_ITEMS) { truncated = true; break outer; }
                        if (count++ > 0) append(",");
                        string(field.getName()); append(":"); value(field.get(value), depth - 1);
                    }
                }
                if (count == 0) { append("\"_type\":"); string(value.getClass().getName()); }
                append("}");
            }
        }
    }
}
