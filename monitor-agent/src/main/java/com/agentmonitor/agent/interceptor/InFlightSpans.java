package com.agentmonitor.agent.interceptor;

import java.util.concurrent.TimeUnit;

/** Core-local invocation barrier. Old tickets never mutate a later Agent session. */
public final class InFlightSpans {
    private static volatile Session current = new Session();
    private InFlightSpans() { }

    public static void startSession() { current = new Session(); }

    public static Ticket enter(boolean root) {
        Session session = current;
        synchronized (session) {
            if (!session.accepting || !MethodSpanAdvice.ACTIVE) return null;
            session.invocations++;
            if (root) session.roots++;
            return new Ticket(session, root);
        }
    }

    /** No new entries; already-entered exits may export before the finite queue is drained. */
    public static Boundary stopAndAwait(long timeoutMillis) {
        Session session = current;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        synchronized (session) {
            session.accepting = false;
            long interruptedRoots = session.roots;
            while (session.invocations > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(session, remaining); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            }
            Boundary result = new Boundary(interruptedRoots, session.invocations);
            long previousRoots = session.boundary == null ? 0 : session.boundary.interruptedRoots();
            session.boundary = new Boundary(Math.max(previousRoots, interruptedRoots), session.invocations);
            return result;
        }
    }

    /** Durable stop-boundary evidence for the raw-file manifest. */
    public static Boundary captureBoundary() {
        Session session = current;
        synchronized (session) {
            return session.boundary == null ? new Boundary(session.roots, session.invocations) : session.boundary;
        }
    }

    public record Boundary(long interruptedRoots, long pendingInvocations) {
        public boolean complete() { return interruptedRoots == 0 && pendingInvocations == 0; }
    }
    private static final class Session {
        private Boundary boundary;
        private boolean accepting = true;
        private long invocations;
        private long roots;
    }
    public static final class Ticket implements AutoCloseable {
        private final Session session;
        private final boolean root;
        private boolean closed;
        private Ticket(Session session, boolean root) { this.session = session; this.root = root; }
        @Override public void close() {
            synchronized (session) {
                if (closed) return;
                closed = true;
                session.invocations--;
                if (root) session.roots--;
                session.notifyAll();
            }
        }
    }
}
