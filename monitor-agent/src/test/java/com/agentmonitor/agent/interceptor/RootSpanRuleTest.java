package com.agentmonitor.agent.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agentmonitor.model.span.SpanKind;
import com.agentmonitor.model.span.SpanAttribute;

class RootSpanRuleTest {

    @BeforeEach
    void setUp() {
        MethodSpanAdvice.ACTIVE = true;
        MethodSpanAdvice.PKG_FILTERS = new String[] { "example." };
        MethodSpanAdvice.CLS_FILTERS = new String[0];
        MethodSpanAdvice.EXCL_PKG_FILTERS = new String[0];
        MethodSpanAdvice.EXCL_CLS_FILTERS = new String[0];
        MethodSpanAdvice.EXCL_REGEX_FILTERS = new String[0];
        MethodSpanAdvice.configureTraceSampling(10, 50);
        MethodSpanAdvice.SPAN_STACK.remove();
        DependencySpanSupport.HTTP_ENABLED = true;
    }

    @AfterEach
    void tearDown() {
        MethodSpanAdvice.ACTIVE = false;
        MethodSpanAdvice.PKG_FILTERS = new String[0];
        MethodSpanAdvice.CLS_FILTERS = new String[0];
        MethodSpanAdvice.configureTraceSampling(10, 50);
        MethodSpanAdvice.SPAN_STACK.remove();
    }

    @Test
    void firstSelectedBusinessMethodBecomesRootWhenThereIsNoHttpEntry() {
        SpanContext span = MethodSpanAdvice.onEnter("example.OrderService", "placeOrder", new Object[0]);

        assertNotNull(span);
        assertTrue(span.parentSpanId.isBlank());
        assertTrue(span.traceId.startsWith("trace-"));
    }

    @Test
    void javaxServletServiceCreatesTheHttpRoot() {
        FakeServlet servlet = new FakeServlet();
        Object[] arguments = { new FakeRequest(), new Object() };

        DependencySpanSupport.Context span = DependencySpanSupport.enter("example.FakeServlet", "service",
                servlet, arguments);

        assertNotNull(span);
        assertEquals(SpanKind.HTTP_SERVER, span.span().kind);
        assertTrue(span.span().parentSpanId.isBlank());
        DependencySpanSupport.exit(span, servlet, arguments, null, null);
    }

    @Test
    void servletRootAndNestedBusinessMethodShareOneSamplingDecision() {
        MethodSpanAdvice.configureTraceSampling(0, 1_000);
        FakeServlet servlet = new FakeServlet();
        Object[] arguments = { new FakeRequest(), new Object() };
        DependencySpanSupport.Context httpRoot = DependencySpanSupport.enter("example.FakeServlet", "service",
                servlet, arguments);

        SpanContext business = MethodSpanAdvice.onEnter("example.OrderService", "placeOrder", new Object[0]);

        assertNotNull(httpRoot);
        assertNotNull(business);
        assertSame(httpRoot.span().traceSampling, business.traceSampling);
        MethodSpanAdvice.removeContext(business);
        DependencySpanSupport.exit(httpRoot, servlet, arguments, null, null);
    }

    @Test
    void httpSpanCapturesOnlyTheRawQueryWithoutReadingTheRequestBody() {
        QueryRequest request = new QueryRequest();

        DependencySpanSupport.Context span = DependencySpanSupport.enter("example.FakeServlet", "service",
                new FakeServlet(), new Object[] { request, new Object() });

        assertNotNull(span);
        assertEquals("id=42&id=43&tag=a%2Bb", span.attributes().get(SpanAttribute.HTTP_PARAMETERS));
        assertEquals("POST", span.attributes().get(SpanAttribute.HTTP_REQUEST_METHOD));
        assertEquals("/orders", span.attributes().get(SpanAttribute.URL_PATH));
        assertEquals("id=42&id=43&tag=a%2Bb", span.attributes().get(SpanAttribute.URL_QUERY));
        assertEquals(0, request.parameterReads);
        assertEquals(0, request.bodyReads);
        DependencySpanSupport.exit(span, null, new Object[] { request }, null, null);
    }

    private static final class FakeServlet implements javax.servlet.Servlet { }
    private static final class FakeRequest implements javax.servlet.ServletRequest { }

    private static final class QueryRequest implements javax.servlet.ServletRequest {
        private int parameterReads;
        private int bodyReads;

        public String getMethod() { return "POST"; }
        public String getRequestURI() { return "/orders"; }
        public String getQueryString() { return "id=42&id=43&tag=a%2Bb"; }
        public java.util.Map<String, String[]> getParameterMap() {
            parameterReads++;
            throw new AssertionError("Agent must not parse parameters from the request body");
        }
        public java.io.InputStream getInputStream() {
            bodyReads++;
            throw new AssertionError("Agent must not read the request body");
        }
        public java.io.Reader getReader() {
            bodyReads++;
            throw new AssertionError("Agent must not read the request body");
        }
    }
}
