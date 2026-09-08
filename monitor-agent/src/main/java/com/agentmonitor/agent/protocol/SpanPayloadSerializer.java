package com.agentmonitor.agent.protocol;

import com.agentmonitor.agent.model.SpanData;

/** Strategy for encoding a completed span independently of any delivery destination. */
public interface SpanPayloadSerializer {
    String serialize(SpanData span);
    String readyMessage();
}
