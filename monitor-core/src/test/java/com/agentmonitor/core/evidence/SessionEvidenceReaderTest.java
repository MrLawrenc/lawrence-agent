package com.agentmonitor.core.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionEvidenceReaderTest {
    @TempDir Path root;
    @org.junit.jupiter.api.BeforeEach void identity()throws Exception{Files.writeString(root.resolve("session.json"),"{\"sessionId\":\"test\"}");}
    private Path metrics()throws Exception{Files.createDirectories(root.resolve("diagnostics"));return root.resolve("diagnostics/metrics.jsonl");}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> data){return (List<Map<String,Object>>)data.get("metrics");}
    @Test void incrementalAppendDoesNotDuplicateAndWaitsForCompleteLine()throws Exception{
        Path file=metrics();Files.writeString(file,"{\"timestampMillis\":1000}\n{\"timestampMillis\":2000");
        var reader=new SessionEvidenceReader();var first=reader.read(root);assertEquals(1,rows(first).size());long offset=((Number)first.get("metricsReadOffset")).longValue();
        assertEquals(offset,((Number)reader.read(root).get("metricsReadOffset")).longValue());
        Files.writeString(file,"}\n",StandardOpenOption.APPEND);assertEquals(2,rows(reader.read(root)).size());assertEquals(2,rows(reader.read(root)).size());
    }
    @Test void rotationTruncationAndSameSizeRewriteDiscardOldSamples()throws Exception{
        Path file=metrics();Files.writeString(file,"{\"timestampMillis\":1000}\n");var reader=new SessionEvidenceReader();reader.read(root);
        Files.writeString(file,"{\"timestampMillis\":2000}\n");assertEquals(2000,rows(reader.read(root)).get(0).get("timestampMillis"));
        Files.delete(file);Files.writeString(file,"{\"timestampMillis\":3}\n");assertEquals(1,rows(reader.read(root)).size());assertEquals(3,rows(reader.read(root)).get(0).get("timestampMillis"));
    }
    @Test void badLineDoesNotHideOtherCategoriesOrValidSamples()throws Exception{
        Files.writeString(metrics(),"bad json\n{\"timestampMillis\":1000}\n");Files.writeString(root.resolve("diagnostics/threads-latest.json"),"{\"threads\":[{\"name\":\"worker\"}]}");
        var result=new SessionEvidenceReader().read(root);assertEquals(1,rows(result).size());assertEquals(1L,((Number)result.get("invalidMetricLines")).longValue());assertTrue(result.get("threads").toString().contains("worker"));assertTrue(result.get("warnings").toString().contains("损坏"));
    }
    @Test void boundedWindowRetainsActualTimestampsAndSwitchDoesNotLeak()throws Exception{
        StringBuilder text=new StringBuilder();for(int i=0;i<1000;i++)text.append("{\"timestampMillis\":").append(i*1000).append("}\n");Files.writeString(metrics(),text);
        var reader=new SessionEvidenceReader();var result=reader.read(root);assertEquals(240,rows(result).size());assertEquals(760000,rows(result).get(0).get("timestampMillis"));
        Path other=Files.createDirectory(root.resolve("other"));Files.writeString(other.resolve("session.json"),"{\"sessionId\":\"other\"}");assertTrue(rows(reader.read(other)).isEmpty());
    }
    @Test void failedJsonRetainsLastSuccessAndReportsOversize()throws Exception{
        Path file=metrics().getParent().resolve("latest.json");Files.writeString(file,"{\"sessionId\":\"test\",\"heap\":12}");var reader=new SessionEvidenceReader();reader.read(root);
        Files.writeString(file,"broken");var broken=reader.read(root);assertTrue(broken.get("latest").toString().contains("test"));assertTrue(broken.get("warnings").toString().contains("保留上次"));
        try(var output=new java.io.RandomAccessFile(file.toFile(),"rw")){output.setLength(64L*1024*1024+1);}
        assertTrue(reader.read(root).get("warnings").toString().contains("64MiB"));
    }
    @Test void overlongLineMakesBoundedProgressAndRecovers()throws Exception{
        Path file=metrics();Files.writeString(file,"x".repeat(9*1024*1024)+"\n{\"timestampMillis\":1}\n");var reader=new SessionEvidenceReader();var first=reader.read(root);assertEquals(8L*1024*1024,((Number)first.get("metricsReadOffset")).longValue());assertEquals(1,rows(reader.read(root)).size());
    }
    @Test void mismatchedReportIsRejectedEvenAfterAValidCachedReport()throws Exception{
        Files.createDirectories(root.resolve("reports"));Path file=root.resolve("reports/performance-report.json");Files.writeString(file,"{\"sessionId\":\"test\",\"captureComplete\":true}");var reader=new SessionEvidenceReader();assertTrue(reader.read(root).get("report").toString().contains("captureComplete"));
        Files.writeString(file,"{\"sessionId\":\"other\",\"captureComplete\":true}");var invalid=reader.read(root);assertEquals(Map.of(),invalid.get("report"));assertTrue(invalid.get("warnings").toString().contains("已拒绝显示"));
    }
    @Test void metricRowsCannotLeakFromAnotherSession()throws Exception{
        Files.writeString(metrics(),"{\"sessionId\":\"other\",\"timestampMillis\":1}\n{\"sessionId\":\"test\",\"timestampMillis\":2}\n");var result=new SessionEvidenceReader().read(root);assertEquals(1,rows(result).size());assertEquals(2,rows(result).get(0).get("timestampMillis"));
    }
    @Test void invalidIdentityNeverFallsBackToCachedEvidence()throws Exception{
        var reader=new SessionEvidenceReader();reader.read(root);Files.writeString(root.resolve("session.json"),"broken");assertThrows(java.io.IOException.class,()->reader.read(root));
    }

}
