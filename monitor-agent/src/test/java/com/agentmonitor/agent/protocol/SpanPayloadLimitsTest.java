package com.agentmonitor.agent.protocol;

import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.agent.model.SpanEventData;
import com.agentmonitor.agent.model.SpanStatus;
import com.agentmonitor.model.span.SpanAttribute;
import com.agentmonitor.model.span.SpanKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanPayloadLimitsTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void hugeMultibyteAndEscapedPayloadFitsTheRealCollectorFrameLimit() throws Exception {
        String large = "\u0000\"\\中文😀".repeat(200_000);
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) attributes.put("attribute-" + i, large);
        attributes.put(SpanAttribute.SAMPLING_REASON, "tail_error");
        attributes.put(SpanAttribute.SAMPLING_RATE, "10");
        List<SpanEventData> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) events.add(new SpanEventData("exception", i, Map.of("stack", large)));
        SpanData input = span(large, attributes, events);
        SpanData limited = SpanPayloadLimits.limit(input);
        assertEquals(limited, SpanPayloadLimits.limit(limited), "Repeated export layers must not keep trimming evidence");
        byte[] bytes = (new JsonSpanPayloadSerializer().serialize(input) + "\n").getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length < 1_048_576, "Serialized bytes must fit the Collector frame, not just Java characters");
        EmbeddedChannel channel = new EmbeddedChannel(new LineBasedFrameDecoder(1_048_576));
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(bytes)));
            ByteBuf frame = channel.readInbound();
            try {
                var document = json.readTree(frame.toString(StandardCharsets.UTF_8));
                assertEquals("trace", document.path("traceId").asText());
                assertEquals("tail_error", document.path("attributes").path(SpanAttribute.SAMPLING_REASON).asText());
                assertEquals("10", document.path("attributes").path(SpanAttribute.SAMPLING_RATE).asText());
                assertEquals("true", document.path("attributes").path(SpanAttribute.PAYLOAD_TRUNCATED).asText());
            } finally { frame.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void clippingEmbeddedCaptureRetainsValidJsonAndAnExplicitMarker() throws Exception {
        SpanData limited = SpanPayloadLimits.limit(span("{\"value\":\"" + "x".repeat(20_000) + "\"}", Map.of(), List.of()));
        assertTrue(json.readTree(limited.arguments()).path("_truncated").asBoolean());
        assertEquals("true", limited.attributes().get(SpanAttribute.PAYLOAD_TRUNCATED));
        assertEquals(limited, SpanPayloadLimits.limit(limited));
    }
    @Test void ordinarySpansRetainTheirInstance() {
        SpanData input = span("{\"id\":42}", Map.of("key", "value"), List.of());
        assertSame(input, SpanPayloadLimits.limit(input));
    }
    private static SpanData span(String args, Map<String, String> attributes, List<SpanEventData> events) {
        return new SpanData("trace", "span", "", "example.Service", "work", "()V", "test", 0, 1, 20,
                SpanStatus.SUCCESS, args, "", "", SpanKind.BUSINESS, attributes, "", events);
    }
}
