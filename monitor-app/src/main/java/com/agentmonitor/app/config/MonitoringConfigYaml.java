package com.agentmonitor.app.config;

import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.output.ExporterType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Iterator;
import java.util.Map;

/** Schema-v2 YAML boundary. Defaults apply only to absent fields, never explicit empty lists. */
public final class MonitoringConfigYaml {
    private static final ObjectMapper JSON = new ObjectMapper().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS, MapperFeature.AUTO_DETECT_IS_GETTERS);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private MonitoringConfigYaml() { }
    public static MonitoringConfig parse(String text) {
        if (text == null || text.isBlank() || text.length() > 1_048_576) throw new IllegalArgumentException("YAML 不能为空且不能超过 1MiB 字符");
        try (JsonParser parser = YAML.createParser(text)) {
            JsonNode input = YAML.readTree(parser);
            if (input == null || !input.isObject()) throw new IllegalArgumentException("配置必须是 YAML 对象");
            if (parser.nextToken() != null) throw new IllegalArgumentException("只支持单个 YAML 文档");
            if (!input.has("version")) throw new IllegalArgumentException("缺少 version: 2");
            ObjectNode merged = tree(MonitoringConfig.defaults());
            merge(merged, input, "");
            if (merged.path("output").path("exporters").isEmpty()) throw new IllegalArgumentException("output.exporters 不能为空");
            merged.set("schemaVersion", merged.remove("version"));
            ArrayNode exporters = JSON.createArrayNode();
            for (JsonNode value : merged.path("output").path("exporters")) {
                exporters.add(ExporterType.fromConfig(value.asText()).orElseThrow(() -> new IllegalArgumentException("未知 exporter: " + value.asText())).name());
            }
            ((ObjectNode)merged.path("output")).set("exporters", exporters);
            ObjectNode sampling = (ObjectNode)merged.path("sampling");
            sampling.put("tailOverflowPolicy", MonitoringConfig.TailOverflowPolicy.fromConfig(sampling.path("tailOverflowPolicy").asText()).name());
            MonitoringConfig config = JSON.treeToValue(merged, MonitoringConfig.class);
            config.requireValid();
            return config;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("YAML 无效: " + error.getMessage(), error); }
    }
    public static String write(MonitoringConfig config) {
        try { return YAML.writeValueAsString(tree(config)); }
        catch (Exception error) { throw new IllegalArgumentException("无法生成 YAML", error); }
    }
    private static ObjectNode tree(MonitoringConfig config) {
        ObjectNode raw = JSON.valueToTree(config);
        ObjectNode result = JSON.createObjectNode();
        result.set("version", raw.remove("schemaVersion"));
        result.setAll(raw);
        ArrayNode exporters = JSON.createArrayNode();
        config.output().exporters().forEach(e -> exporters.add(e.configValue()));
        ((ObjectNode)result.path("output")).set("exporters", exporters);
        ((ObjectNode)result.path("sampling")).put("tailOverflowPolicy", config.sampling().tailOverflowPolicy().configValue());
        return result;
    }
    private static void merge(ObjectNode defaults, JsonNode supplied, String path) {
        Iterator<Map.Entry<String, JsonNode>> fields = supplied.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String name = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
            JsonNode expected = defaults.get(field.getKey()), value = field.getValue();
            if (expected == null) throw new IllegalArgumentException("未知配置项: " + name);
            if (value.isNull()) throw new IllegalArgumentException(name + " 不能为 null");
            if (expected.isObject()) {
                if (!value.isObject()) throw new IllegalArgumentException(name + " 必须是对象");
                merge((ObjectNode)expected, value, name);
            } else {
                boolean valid = expected.isArray() ? value.isArray() && allStrings(value)
                        : expected.isBoolean() ? value.isBoolean()
                        : expected.isNumber() ? value.isIntegralNumber() && value.canConvertToLong()
                        : value.isTextual();
                if (!valid) throw new IllegalArgumentException(name + " 的类型不正确");
                defaults.set(field.getKey(), value.deepCopy());
            }
        }
    }
    private static boolean allStrings(JsonNode array) {
        for (JsonNode value : array) if (!value.isTextual()) return false;
        return true;
    }
}
