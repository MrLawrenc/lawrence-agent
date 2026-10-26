package com.agentmonitor.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.agent.exporter.ExporterDrainResult;
import com.agentmonitor.agent.interceptor.InFlightSpans;
import com.agentmonitor.agent.interceptor.MethodSpanAdvice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StopCaptureCompletenessTest {
    @BeforeEach void setup() { InFlightSpans.startSession(); MethodSpanAdvice.ACTIVE = true; }
    @AfterEach void cleanup() { MethodSpanAdvice.ACTIVE = false; InFlightSpans.startSession(); }

    @Test void flushedQueueCannotHideUnfinishedRoots() {
        var root = InFlightSpans.enter(true);
        var child = InFlightSpans.enter(false);
        var boundary = InFlightSpans.stopAndAwait(0);
        var result = AgentMain.captureDrainResult(ExporterDrainResult.success(), boundary);
        assertFalse(result.drained());
        assertEquals(2, result.pendingSpans());
        assertTrue(result.detail().contains("1 条在途根调用"));
        assertNull(InFlightSpans.enter(true));
        child.close(); root.close();
    }
    @Test void aRootThatFinishesDuringStopStillCarriesItsTruncationBoundary() {
        var result = AgentMain.captureDrainResult(ExporterDrainResult.success(), new InFlightSpans.Boundary(1, 0));
        assertFalse(result.drained());
        assertEquals(0, result.pendingSpans());
        assertTrue(result.detail().contains("链路可能被截断"));
    }
    @Test void completeCaptureKeepsTransportResultAndFailures() {
        var transport = ExporterDrainResult.failed(3, 4, 5, "transport");
        assertSame(transport, AgentMain.captureDrainResult(transport, new InFlightSpans.Boundary(0, 0)));
        var result = AgentMain.captureDrainResult(transport, new InFlightSpans.Boundary(1, 2));
        assertEquals(5, result.pendingSpans());
        assertEquals(4, result.droppedSpans());
        assertEquals(5, result.rejectedDestinations());
        assertTrue(result.detail().contains("transport"));
    }
    @Test void historicalOutputLossCannotBeHiddenByASuccessfulFlush() {
        var loss = AgentMain.captureDrainResult(ExporterDrainResult.success(4, 2), new InFlightSpans.Boundary(0, 0));
        assertFalse(loss.drained());
        assertEquals(4, loss.droppedSpans());
        assertEquals(2, loss.rejectedDestinations());
        assertTrue(loss.detail().contains("采集数据不完整"));
    }

    @Test void lateExitsCannotDecrementANewSessionAndTicketsAreIdempotent() {
        var old = InFlightSpans.enter(true);
        InFlightSpans.startSession();
        var current = InFlightSpans.enter(true);
        old.close(); old.close();
        assertEquals(1, InFlightSpans.stopAndAwait(0).pendingInvocations());
        current.close(); current.close();
        assertTrue(InFlightSpans.stopAndAwait(0).complete());
    }
    @Test void waitIsBoundedWhenAnInvocationNeverFinishes() {
        var ticket = InFlightSpans.enter(true);
        long started = System.nanoTime();
        var boundary = InFlightSpans.stopAndAwait(20);
        assertEquals(1, boundary.pendingInvocations());
        assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
        ticket.close();
    }
}
