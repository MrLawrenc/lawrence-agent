import com.agentmonitor.core.model.SpanEvent;
import com.agentmonitor.core.service.MonitoringRuntime;
import com.agentmonitor.core.service.SessionAnalyzer;
import com.agentmonitor.model.trace.TraceNode;
import java.nio.file.Path;
import java.util.Map;
/** This consumer compiles with only core/model outputs, without implementation dependencies. */
final class CoreApiProbe {
    SpanEvent parse(String data) { return SpanEvent.parse(data); }
    Map<String, Object> analyze(Path path) throws Exception { return new SessionAnalyzer().analyze(path); }
    void subscribe(MonitoringRuntime runtime) { runtime.setOnRootNode(this::read); }
    void read(TraceNode root) { root.getChildren().forEach(this::read); }
}
