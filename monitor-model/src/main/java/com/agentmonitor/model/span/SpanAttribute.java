package com.agentmonitor.model.span;

/** Shared, JSON-safe names for safe dependency metadata carried on a completed Span. */
public final class SpanAttribute {

    public static final String DEPENDENCY_TYPE = "dependencyType";
    public static final String DATASOURCE_NAME = "datasourceName";
    public static final String MAPPER_ID = "mapperId";
    public static final String SQL = "sql";
    public static final String SQL_FINGERPRINT = "sqlFingerprint";
    public static final String SQL_TYPE = "sqlType";
    public static final String SQL_PARAMETERS = "sqlParameters";
    public static final String REMOTE_SERVICE = "remoteService";
    public static final String HTTP_METHOD = "httpMethod";
    public static final String ROUTE_TEMPLATE = "routeTemplate";
    /** Raw URL query component, captured without reading the request body. */
    public static final String HTTP_PARAMETERS = "httpParameters";
    public static final String STATUS_CODE = "statusCode";

    // OpenTelemetry-compatible semantic attributes. Legacy names above stay in the payload so
    // existing session viewers and reports remain readable during the schema transition.
    public static final String ERROR_TYPE = "error.type";
    public static final String EXCEPTION_TYPE = "exception.type";
    public static final String EXCEPTION_MESSAGE = "exception.message";
    public static final String EXCEPTION_STACKTRACE = "exception.stacktrace";

    public static final String HTTP_REQUEST_METHOD = "http.request.method";
    public static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";
    public static final String HTTP_ROUTE = "http.route";
    public static final String URL_PATH = "url.path";
    public static final String URL_QUERY = "url.query";
    public static final String URL_SCHEME = "url.scheme";
    public static final String SERVER_ADDRESS = "server.address";
    public static final String SERVER_PORT = "server.port";
    public static final String NETWORK_PROTOCOL_VERSION = "network.protocol.version";

    public static final String DB_SYSTEM_NAME = "db.system.name";
    public static final String DB_NAMESPACE = "db.namespace";
    public static final String DB_OPERATION_NAME = "db.operation.name";
    public static final String DB_OPERATION_BATCH_SIZE = "db.operation.batch.size";
    public static final String DB_QUERY_TEXT = "db.query.text";
    public static final String DB_QUERY_SUMMARY = "db.query.summary";
    public static final String DB_RESPONSE_STATUS_CODE = "db.response.status_code";

    private SpanAttribute() { }
}
