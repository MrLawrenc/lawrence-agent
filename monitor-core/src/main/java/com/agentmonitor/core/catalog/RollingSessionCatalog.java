package com.agentmonitor.core.catalog;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded, read-only rolling window navigation; moved captures resolve by validated window ID. */
public final class RollingSessionCatalog {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    public Map<String,Object> list(Path selected)throws IOException {
        Path root=selected.toRealPath();JsonNode identity=read(root,root.resolve("session.json"),65536);
        String master=identity.path("sessionId").asText();if(master.isBlank())throw new IOException("主会话身份缺失");
        Path manifest=root.resolve("rolling-capture.json");if(!Files.isRegularFile(manifest))return Map.of("windows",List.of(),"rolling",false);
        JsonNode capture=read(root,manifest,16*1024*1024);if(!master.equals(capture.path("sessionId").asText()))throw new IOException("滚动清单身份不匹配");
        Map<String,Path> paths=new HashMap<>();Path folder=root.resolve("windows");
        if(Files.isDirectory(folder)&&folder.toRealPath().startsWith(root))try(var files=Files.walk(folder,2)){
            for(Path candidate:files.limit(20001).toList())if(!Files.isSymbolicLink(candidate)&&Files.isRegularFile(candidate.resolve("session.json"),LinkOption.NOFOLLOW_LINKS)){
                JsonNode marker=read(root,candidate.resolve("session.json"),65536);String id=marker.path("sessionId").asText();if(paths.putIfAbsent(id,candidate)!=null)throw new IOException("重复窗口身份："+id);
            }
        }
        List<Map<String,Object>> windows=new ArrayList<>();List<String> warnings=new ArrayList<>();
        int count=0;for(JsonNode row:capture.path("windows")){
            if(++count>10000){warnings.add("窗口清单超过 10000 项，已截断");break;}
            String id=row.path("sessionId").asText();Path path=paths.get(id);Map<String,Object> item=new LinkedHashMap<>();
            item.put("sequence",row.path("sequence").asInt());item.put("sessionId",id);item.put("startedAtMillis",row.path("startedAtMillis").asLong());item.put("endedAtMillis",row.path("endedAtMillis").asLong());item.put("roots",row.path("roots").asLong());item.put("partialEvidence",true);
            if(path==null){item.put("status","MISSING");item.put("checkpoint","UNKNOWN");warnings.add("找不到窗口："+id);}
            else try {
                JsonNode window=read(root,path.resolve("window.json"),65536);
                if(!id.equals(window.path("sessionId").asText())||!master.equals(window.path("sourceSessionId").asText()))throw new IOException("窗口/主会话身份不匹配");
                item.put("sessionDirectory",path.toString());item.put("status","AVAILABLE");item.put("omittedTraceRecords",window.path("omittedTraceRecords").asLong());
                Path checkpoint=path.resolve("diagnostics/jfr-checkpoint.json");String state=window.path("checkpointRequired").asBoolean()?"PENDING":"NOT_REQUESTED";
                if(Files.isRegularFile(checkpoint)){JsonNode value=read(root,checkpoint,1024*1024);if(!master.equals(value.path("sourceSessionId").asText()))throw new IOException("检查点身份不匹配");state=value.path("status").asText("UNKNOWN");}
                item.put("checkpoint",state);long bytes=0;try(var files=Files.walk(path)){for(Path file:files.limit(10001).toList())if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))bytes+=Files.size(file);}item.put("diskBytes",bytes);
            }catch(Exception error){item.put("status","UNREADABLE");item.remove("sessionDirectory");item.put("error",error.getMessage());warnings.add(id+"："+error.getMessage());}
            windows.add(item);
        }
        return Map.of("rolling",true,"sessionId",master,"status",capture.path("status").asText(),"windowMillis",capture.path("windowMillis").asLong(),"maxBytes",capture.path("maxBytes").asLong(),"budgetReached",capture.path("budgetReached").asBoolean(),"windows",windows,"warnings",warnings,"boundary","窗口始终是局部证据；总完整性与还原状态以主会话最终回执为准。");
    }
    private static JsonNode read(Path root,Path path,long limit)throws IOException{if(!Files.isRegularFile(path)||!path.toRealPath().startsWith(root)||Files.size(path)>limit)throw new IOException("缺失、超限或目录外文件："+path.getFileName());JsonNode value=JSON.readTree(path.toFile());if(value==null||!value.isObject())throw new IOException("无效 JSON 对象");return value;}
}
