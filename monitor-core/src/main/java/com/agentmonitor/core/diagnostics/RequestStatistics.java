package com.agentmonitor.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;

/** Read-only independent request statistics, bound to a validated capture identity. */
public final class RequestStatistics {
    public Map<String,Object> read(Path selected)throws Exception{
        Path root=selected.toRealPath();var marker=SymptomDiagnosis.read(root,"session.json",null);
        var data=SymptomDiagnosis.read(root,"agent-logs/request-metrics.json",marker);
        if(data.isMissingNode())throw new IllegalArgumentException("No independent Servlet request metrics; capture with dependencies.http=true");
        if(!data.path("schemaVersion").isIntegralNumber()||data.path("schemaVersion").intValue()!=1||!data.path("endpoints").isArray()||!data.path("captureComplete").isBoolean())throw new IllegalArgumentException("Invalid request metric schema");
        return Collections.unmodifiableMap(new ObjectMapper().convertValue(data,Map.class));
    }
}
