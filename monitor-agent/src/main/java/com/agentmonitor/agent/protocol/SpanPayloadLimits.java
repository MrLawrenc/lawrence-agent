package com.agentmonitor.agent.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.agent.model.SpanEventData;
import com.agentmonitor.model.span.SpanAttribute;

/** Limits apply equally to file and network output, including attributes and exception events. */
public final class SpanPayloadLimits {
    // At most six wire bytes per UTF-16 code unit after JSON escaping. Leave ample room for keys.
    private static final int TOTAL_CHARACTERS = 65_536;
    private static final int FIELD_CHARACTERS = 8_192;
    private static final int ATTRIBUTE_COUNT = 32;
    private static final int EVENT_COUNT = 4;
    private SpanPayloadLimits() { }

    public static SpanData limit(SpanData span) {
        if (withinLimits(span)) return span;
        Budget budget = new Budget();
        String trace = budget.text(span.traceId(), 256), id = budget.text(span.spanId(), 256);
        String parent = budget.text(span.parentSpanId(), 256);
        String cls = budget.text(span.className(), 1024), method = budget.text(span.methodName(), 1024);
        String signature = budget.text(span.signature(), 2048), thread = budget.text(span.threadName(), 256);
        String description = budget.text(span.statusDescription(), FIELD_CHARACTERS);
        Map<String, String> attributes = budget.attributes(span.attributes());
        var events = new ArrayList<SpanEventData>();
        for (SpanEventData event : span.events()) {
            if (events.size() == EVENT_COUNT) { budget.truncated = true; break; }
            events.add(new SpanEventData(budget.text(event.name(), 128), event.timestampEpochMillis(),
                    budget.attributes(event.attributes())));
        }
        String arguments = budget.structured(span.arguments());
        String returned = budget.structured(span.returnValue());
        String stack = budget.text(span.stackTrace(), FIELD_CHARACTERS);
        if (budget.truncated) attributes.put(SpanAttribute.PAYLOAD_TRUNCATED, "true");
        return new SpanData(trace, id, parent, cls, method, signature, thread, span.depth(),
                span.startedAtEpochMillis(), span.durationNanos(), span.status(), arguments, returned,
                stack, span.kind(), attributes, description, events);
    }

    private static boolean withinLimits(SpanData span) {
        String[] identity = {span.traceId(), span.spanId(), span.parentSpanId(), span.className(),
                span.methodName(), span.signature(), span.threadName()};
        int[] limits = {256, 256, 256, 1024, 1024, 2048, 256};
        long total = 0;
        for (int i = 0; i < identity.length; i++) {
            if (identity[i] == null || identity[i].length() > limits[i]) return false;
            total += identity[i].length();
        }
        for (String value : new String[]{span.arguments(), span.returnValue(), span.stackTrace(), span.statusDescription()}) {
            if (value == null || value.length() > FIELD_CHARACTERS) return false;
            total += value.length();
        }
        long attributes = attributeCharacters(span.attributes());
        if (attributes < 0 || span.events().size() > EVENT_COUNT) return false;
        total += attributes;
        for (SpanEventData event : span.events()) {
            if (event.name().length() > 128) return false;
            attributes = attributeCharacters(event.attributes());
            if (attributes < 0) return false;
            total += event.name().length() + attributes;
        }
        return total <= TOTAL_CHARACTERS;
    }
    private static long attributeCharacters(Map<String, String> attributes) {
        if (attributes.size() > ATTRIBUTE_COUNT) return -1;
        long count = 0;
        for (var entry : attributes.entrySet()) {
            if (entry.getKey().isEmpty() || entry.getKey().length() > 128 || entry.getValue().length() > FIELD_CHARACTERS) return -1;
            count += entry.getKey().length() + entry.getValue().length();
        }
        return count;
    }

    public static Map<String, String> attributes(Map<String, String> source) {
        Budget budget = new Budget();
        Map<String, String> result = budget.attributes(source);
        if (budget.truncated) result.put(SpanAttribute.PAYLOAD_TRUNCATED, "true");
        return result;
    }

    private static final class Budget {
        private int remaining = TOTAL_CHARACTERS - 512;
        private boolean truncated;
        private String text(String value, int max) {
            if (value == null) return "";
            int end = Math.min(value.length(), Math.min(max, remaining));
            if (end < value.length()) {
                truncated = true;
                if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            }
            remaining -= end;
            return value.substring(0, end);
        }
        private String structured(String value) {
            String text = text(value, FIELD_CHARACTERS);
            return value != null && text.length() < value.length()
                    ? "{\"_truncated\":true,\"_reason\":\"payload_budget\"}" : text;
        }
        private Map<String, String> attributes(Map<String, String> source) {
            Map<String, String> result = new LinkedHashMap<>();
            // These attributes describe whether AI may trust the remaining evidence.
            for (String key : new String[]{SpanAttribute.SAMPLING_REASON, SpanAttribute.SAMPLING_RATE,
                    SpanAttribute.CAPTURE_TRUNCATED, SpanAttribute.PAYLOAD_TRUNCATED,
                    SpanAttribute.BATCH_PARAMETERS_TRUNCATED, SpanAttribute.BATCH_PARAMETER_SAMPLES,
                    SpanAttribute.DB_OPERATION_BATCH_SIZE, SpanAttribute.OBSERVER_EXCLUDED_NANOS}) {
                if (source.containsKey(key)) result.put(text(key, 128), text(source.get(key), 128));
            }
            int count = result.size();
            for (var entry : source.entrySet()) {
                if (result.containsKey(entry.getKey())) continue;
                if (count++ == ATTRIBUTE_COUNT - 1) { truncated = true; break; }
                String key = text(entry.getKey(), 128);
                if (key.isEmpty()) { truncated = true; continue; }
                result.put(key, text(entry.getValue(), FIELD_CHARACTERS));
            }
            return result;
        }
    }
}
