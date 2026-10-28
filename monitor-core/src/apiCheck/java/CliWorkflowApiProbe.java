import com.agentmonitor.core.catalog.SessionCatalog;
import com.agentmonitor.core.ai.AiAnalysisValidator;
import java.nio.file.Path;
import java.util.Map;
final class CliWorkflowApiProbe {
    Map<String,Object> show(Path path) throws Exception{return new SessionCatalog().show(path);}
    AiAnalysisValidator.Result verify(Path session,Path response){return new AiAnalysisValidator().verify(session,response,null);}
}
final class EvidenceApiProbe {
    java.util.Map<String,Object> diagnose(java.nio.file.Path path)throws Exception{return new com.agentmonitor.core.diagnostics.SymptomDiagnosis().analyze(path,"latency");}
    java.util.Map<String,Object> requests(java.nio.file.Path path)throws Exception{return new com.agentmonitor.core.diagnostics.RequestStatistics().read(path);}
    java.util.Map<String,Object> distributed(java.util.List<java.nio.file.Path> paths,String trace)throws Exception{return new com.agentmonitor.core.diagnostics.DistributedTrace().correlate(paths,trace);}
    java.util.Map<String,Object> query(java.nio.file.Path path) throws Exception {
        return new com.agentmonitor.core.evidence.EvidenceStore().query(path,new com.agentmonitor.core.evidence.EvidenceStore.Query(null,null,null,null,null,null,50,0));
    }
    java.util.Map<String,Object> timeline(java.nio.file.Path path) throws Exception {
        return new com.agentmonitor.core.evidence.TimelineService().query(path,1L,1000L,null,null,100);
    }
}
