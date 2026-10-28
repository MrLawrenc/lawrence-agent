package com.agentmonitor.core.ai;

import com.agentmonitor.core.report.SessionAnalysisPrompt;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Native, read-only validation of this project's AI contract and exact local JSON evidence. */
public final class AiAnalysisValidator {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(128).maxStringLength(4*1024*1024).build()).build()).enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final long MAX_SOURCE=128L*1024*1024;
    private static final int MAX_DOCUMENT=4*1024*1024;
    private static final Set<String> IDENTITY=Set.of("sessionId","traceId","method","rootMethod","className","methodName","signature","kind");
    public record Result(boolean valid,String sessionId,int findings,int verifiedEvidenceItems,List<String> errors,String scope) { }
    private static final String SCOPE="Contract, quality gate and exact local JSON citations; does not prove root cause, proposed benefit or model accuracy";
    public Result verify(Path selected,Path responseFile,Path schemaFile) {
        String id="";int findings=0,checked=0;
        try {
            Path session=selected.toRealPath();require(Files.isDirectory(session),"Session directory does not exist");
            JsonNode response=readDocument(responseFile,MAX_DOCUMENT),schema;
            Path exported=session.resolve("reports/ai-analysis-output.schema.json");
            if(schemaFile!=null)schema=readDocument(schemaFile,MAX_DOCUMENT);
            else if(Files.isRegularFile(exported))schema=readDocument(safe(session,exported),MAX_DOCUMENT);
            else schema=JSON.readTree(SessionAnalysisPrompt.outputSchema());
            schema(response,schema,"$");id=response.path("sessionId").asText();findings=response.path("findings").size();
            JsonNode report=reportMetadata(session),identity=optional(session,"session.json"),manifest=optional(session,"spans/manifest.json"),diagnostics=optional(session,"diagnostics/manifest.json");
            JsonNode requests=optional(session,"agent-logs/request-metrics.json");
            boolean known=false;
            for(JsonNode item:List.of(report,identity,diagnostics,requests))if(!item.path("sessionId").asText().isBlank()) {
                known=true;require(id.equals(item.path("sessionId").asText()),"Session ID mismatch across artifacts");
            }
            require(known,"No session identity evidence");
            JsonNode quality=response.path("quality"),capture=report.path("captureQuality"),exporter=capture.path("agentExporter");
            for(String key:List.of("captureComplete","analysisReady"))require(same(quality.path(key),report.path(key).isMissingNode()?JSON.nullNode():report.path(key)),"Quality differs from source or unknown: "+key);
            boolean complete=report.path("schemaVersion").isIntegralNumber() && report.path("schemaVersion").intValue()==1 && positive(report.path("observedRootTraces"))
                    && report.path("status").asText().equals("COMPLETED") && truth(report.path("captureComplete")) && truth(report.path("analysisReady"))
                    && !identity.path("sessionId").asText().isBlank() && truth(exporter.path("reported")) && truth(manifest.path("captureComplete"))
                    && nonnegative(capture.path("acceptedSpans")) && same(capture.path("acceptedSpans"),exporter.path("enqueuedSpans"));
            for(String field:List.of("queueDroppedSpans","deliveryDroppedSpans","rejectedDestinations","pendingSpans"))complete&=zero(exporter.path(field));
            for(String field:List.of("discardedSpans","unreceivedSpans"))complete&=zero(capture.path(field));
            if(quality.path("verdict").asText().equals("usable"))require(complete,"Usable verdict is unsupported by completeness evidence");
            else require(!quality.path("issues").isEmpty(),"Partial/insufficient verdict must explain missing evidence");
            boolean noTraceRoots=!positive(report.path("observedRootTraces"));
            boolean hasDiagnostics=id.equals(diagnostics.path("sessionId").asText()) && diagnostics.path("schemaVersion").isIntegralNumber() && diagnostics.path("schemaVersion").intValue()==1
                    && (positive(diagnostics.path("samples")) || positive(diagnostics.path("threadSnapshots")));
            boolean hasRequests=id.equals(requests.path("sessionId").asText())&&requests.path("schemaVersion").isIntegralNumber()&&requests.path("schemaVersion").intValue()==1
                    && truth(requests.path("enabled"))&&requests.path("status").asText().equals("FINAL")&&positive(requests.path("completedRequests"))&&requests.path("endpoints").isArray()&&!requests.path("endpoints").isEmpty();
            if(noTraceRoots && !hasDiagnostics&&!hasRequests)require(response.path("findings").isEmpty(),"No valid trace, diagnostic or request metric samples: findings must be empty");
            for(JsonNode finding:response.path("findings")) {
                boolean citesDiagnostics=false;
                require(finding.path("evidence").size()<=64,"At most 64 evidence references per finding");
                for(JsonNode evidence:finding.path("evidence")) {
                    Path file=safe(session,session.resolve(evidence.path("file").asText()));
                    citesDiagnostics |= file.startsWith(session.resolve("diagnostics"));
                    citesDiagnostics |= hasRequests&&file.equals(session.resolve("agent-logs/request-metrics.json"));
                    String name=file.getFileName().toString();require(name.endsWith(".json") || name.endsWith(".json.gz") || name.endsWith(".jsonl") || name.endsWith(".jsonl.gz"),"Evidence must be JSON/JSONL or gzip");
                    String pointer=evidence.path("jsonPointer").asText();long line=evidence.path("line").asLong(0);require(!evidence.has("line")||evidence.path("line").canConvertToLong(),"JSONL line out of range");
                    if(evidence.has("evidenceId"))new com.agentmonitor.core.evidence.EvidenceStore().verifyReference(session,evidence.path("evidenceId").asText(),evidence.path("file").asText(),line,pointer);
                    Resolution resolved=resolve(file,pointer,line);
                    if(!resolved.session.isEmpty())require(id.equals(resolved.session),"Evidence artifact belongs to another session");
                    require(same(resolved.value,evidence.path("value")),"Evidence value differs from source: "+pointer);
                    String expected=unit(resolved.value,pointer);if(expected!=null)require(expected.equals(evidence.path("unit").asText()),"Evidence unit mismatch: expected "+expected);
                    require(nullable(evidence.path("traceId")).equals(resolved.trace),"Trace ID differs from nearest cited context or is missing");
                    require(nullable(evidence.path("method")).equals(resolved.method),"Method differs from nearest cited context or is missing");checked++;
                }
                if(noTraceRoots)require(citesDiagnostics,"Rootless findings require a verified diagnostic or independent request metric JSON citation");
            }
            return new Result(true,id,findings,checked,List.of(),SCOPE);
        } catch(Exception error) {return new Result(false,id,findings,checked,List.of(Objects.toString(error.getMessage(),error.getClass().getSimpleName())),SCOPE);}
    }
    private static boolean truth(JsonNode value){return value.isBoolean() && value.booleanValue();}
    private static boolean nonnegative(JsonNode value){return value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>=0;}
    private static boolean positive(JsonNode value){return nonnegative(value) && value.longValue()>0;}
    private static boolean zero(JsonNode value){return nonnegative(value) && value.longValue()==0;}
    private static String nullable(JsonNode value){return value.isNull() || value.isMissingNode()?"":value.asText();}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
    private static Path safe(Path session,Path file) throws IOException {Path actual=file.toRealPath();require(actual.startsWith(session),"Evidence path escapes session directory");require(Files.isRegularFile(actual),"Evidence file is not regular");return actual;}
    private static JsonNode reportMetadata(Path root) throws IOException {
        Path file=root.resolve("reports/performance-report.json");if(!Files.exists(file))return JSON.createObjectNode();file=safe(root,file);
        var result=JSON.createObjectNode();Set<String> wanted=Set.of("schemaVersion","sessionId","status","captureComplete","analysisReady","observedRootTraces","captureQuality");
        try(InputStream input=limited(Files.newInputStream(file),MAX_SOURCE);JsonParser parser=JSON.getFactory().createParser(input)) {
            require(parser.nextToken()==JsonToken.START_OBJECT,"Report is not an object");Set<String> seen=new HashSet<>();
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                require(parser.currentToken()==JsonToken.FIELD_NAME,"Malformed report");String name=parser.currentName();require(seen.add(name),"Duplicate report field");parser.nextToken();
                if(wanted.contains(name))result.set(name,walk(parser,new String[0],0).value);else parser.skipChildren();
            }
            require(parser.nextToken()==null,"More than one report JSON document");
        }
        return result;
    }
    private static JsonNode optional(Path root,String name) throws IOException {Path file=root.resolve(name);return Files.exists(file)?readDocument(safe(root,file),MAX_DOCUMENT):JSON.createObjectNode();}
    private static JsonNode readDocument(Path path,long max) throws IOException {
        try(InputStream input=limited(Files.newInputStream(path),max);var parser=JSON.getFactory().createParser(input)) {
            JsonNode value=JSON.readTree(parser);require(value!=null,"Empty JSON document");require(parser.nextToken()==null,"More than one JSON document");return value;
        }
    }
    private static InputStream limited(InputStream source,long max){return new FilterInputStream(source){long read;
        private void added(long n) throws IOException {if(n>0 && (read+=n)>max)throw new IOException("JSON input exceeds "+max+" bytes");}
        public int read() throws IOException {int b=super.read();if(b>=0)added(1);return b;}
        public int read(byte[] bytes,int off,int len) throws IOException {int n=in.read(bytes,off,len);added(n);return n;}
    };}
    private static boolean same(JsonNode a,JsonNode b) {
        if(a.isNumber() && b.isNumber())return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isObject() && b.isObject()){if(a.size()!=b.size())return false;var fields=a.fields();while(fields.hasNext()){var entry=fields.next();if(!b.has(entry.getKey()) || !same(entry.getValue(),b.get(entry.getKey())))return false;}return true;}
        if(a.isArray() && b.isArray()){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!same(a.get(i),b.get(i)))return false;return true;}
        return a.equals(b);
    }
    private static void schema(JsonNode value,JsonNode rule,String at) {
        JsonNode type=rule.path("type");
        if(!type.isMissingNode()){boolean match=false;if(type.isArray()){for(JsonNode option:type)match|=type(value,option.asText());}else match=type(value,type.asText());require(match,at+": invalid type");}
        if(rule.has("const"))require(same(value,rule.get("const")),at+": invalid constant");
        if(rule.has("enum")){boolean found=false;for(JsonNode option:rule.path("enum"))found|=same(value,option);require(found,at+": invalid enum value");}
        if(value.isNumber()){if(rule.has("minimum"))require(value.decimalValue().compareTo(rule.path("minimum").decimalValue())>=0,at+": below minimum");if(rule.has("maximum"))require(value.decimalValue().compareTo(rule.path("maximum").decimalValue())<=0,at+": above maximum");}
        if(value.isObject()) {
            for(JsonNode field:rule.path("required"))require(value.has(field.asText()),at+": missing required field "+field.asText());
            var fields=value.fields();while(fields.hasNext()){var entry=fields.next();JsonNode child=rule.path("properties").get(entry.getKey());
                require(child!=null || !rule.path("additionalProperties").isBoolean() || rule.path("additionalProperties").asBoolean(),at+": unexpected field "+entry.getKey());
                if(child!=null)schema(entry.getValue(),child,at+"."+entry.getKey());}
        }
        if(value.isArray()){require(value.size()>=rule.path("minItems").asInt(0) && value.size()<=rule.path("maxItems").asInt(Integer.MAX_VALUE),at+": invalid item count");int i=0;for(JsonNode child:value)schema(child,rule.path("items"),at+"["+(i++)+"]");}
        if(value.isTextual()){require(value.textValue().codePointCount(0,value.textValue().length())>=rule.path("minLength").asInt(0),at+": empty text");if(rule.has("pattern"))require(Pattern.matches(rule.path("pattern").asText(),value.textValue()),at+": invalid pattern");}
    }
    private static boolean type(JsonNode value,String name){return switch(name){case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isTextual();case "boolean"->value.isBoolean();case "null"->value.isNull();case "integer"->value.isIntegralNumber();case "number"->value.isNumber();default->false;};}
    private static String unit(JsonNode actual,String pointer) {
        String token=pointer.substring(pointer.lastIndexOf('/')+1).replace("~1","/").replace("~0","~").toLowerCase(Locale.ROOT);
        if(actual.isBoolean())return "boolean";
        String lower=pointer.toLowerCase(Locale.ROOT);
        if(lower.contains("/latencybucketcounts/"))return "count";
        if(lower.contains("/latencybucketupperboundsnanos/"))return "nanoseconds";
        if(token.equals("completedpersecond"))return "per_second";
        if(Set.of("errorrate","observederrorfraction").contains(token))return "ratio";
        if(Set.of("completed","unknownstatus").contains(token)||token.endsWith("requests"))return "count";
        if(token.equals("processcputime"))return "nanoseconds";
        if(token.equals("processcpuload") || token.equals("cpuload") || token.endsWith("ratio"))return "ratio";
        String lowerPointer=pointer.toLowerCase(Locale.ROOT);
        if((lowerPointer.contains("/heap/") || lowerPointer.contains("/nonheap/")) && Set.of("used","committed","max","init").contains(token))return "bytes";
        if(token.endsWith("nanos"))return "nanoseconds";if(token.endsWith("millis"))return "milliseconds";
        if(token.endsWith("percent"))return "percent";if(token.endsWith("bytes"))return "bytes";if(token.endsWith("sizemb"))return "mebibytes";
        if(actual.isNumber() || (actual.isTextual() && actual.asText().matches("-?[0-9]+")))for(String suffix:List.of("spans","calls","errors","classes","methods","traces","collections","occurrences","subtrees","count","capacity","destinations"))if(token.endsWith(suffix))return "count";
        return actual.isTextual() || actual.isContainerNode()?"text":null;
    }
    private static final class Resolution {
        JsonNode value;String trace="",method="",session="";Resolution(JsonNode value){this.value=value;}
        void inherit(Map<String,String> context) {
            if(session.isEmpty())session=context.getOrDefault("sessionId","");
            if(trace.isEmpty())trace=context.getOrDefault("traceId","");
            if(method.isEmpty()) {
                if(context.containsKey("method"))method=context.get("method");else if(context.containsKey("rootMethod"))method=context.get("rootMethod");
                else if(context.containsKey("className") && context.containsKey("methodName"))method=context.get("className")+"#"+context.get("methodName")+context.getOrDefault("signature","")+" ["+context.getOrDefault("kind","BUSINESS")+"]";
            }
        }
    }
    private static Resolution resolve(Path path,String pointer,long line) throws Exception {
        require(pointer.isEmpty() || (pointer.startsWith("/") && !Pattern.compile("~(?![01])").matcher(pointer).find()),"Invalid JSON Pointer");
        String[] tokens=pointer.isEmpty()?new String[0]:Arrays.stream(pointer.substring(1).split("/",-1)).map(s->s.replace("~1","/").replace("~0","~")).toArray(String[]::new);
        boolean jsonl=path.toString().matches(".*\\.jsonl(\\.gz)?");
        if(jsonl){require(line>0,"JSONL citation requires a positive line");byte[] bytes=com.agentmonitor.core.evidence.EvidenceStore.recordBytes(path,line);
            try(JsonParser parser=JSON.getFactory().createParser(bytes)){parser.nextToken();Resolution result=walk(parser,tokens,0);require(result!=null,"JSONL pointer does not exist");return result;}}
        require(line==0,"JSON citation must not specify line");
        try(InputStream raw=limited(Files.newInputStream(path),MAX_SOURCE);InputStream input=limited(path.toString().endsWith(".gz")?new GZIPInputStream(raw):raw,MAX_SOURCE);JsonParser parser=JSON.getFactory().createParser(input)) {
            require(parser.nextToken()!=null,"Empty evidence JSON");Resolution result=walk(parser,tokens,0);require(result!=null,"Evidence pointer does not exist: "+pointer);require(parser.nextToken()==null,"More than one source JSON document");return result;
        }
    }
    /** Preserve numeric lexical precision instead of converting through a binary double. */
    private static void copyValue(JsonParser parser,JsonGenerator out) throws IOException {
        if(parser.currentToken()==JsonToken.START_OBJECT) {
            out.writeStartObject();
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                require(parser.currentToken()==JsonToken.FIELD_NAME,"Malformed JSON object");out.writeFieldName(parser.currentName());parser.nextToken();copyValue(parser,out);
            }
            out.writeEndObject();
        } else if(parser.currentToken()==JsonToken.START_ARRAY) {
            out.writeStartArray();while(parser.nextToken()!=JsonToken.END_ARRAY)copyValue(parser,out);out.writeEndArray();
        } else if(parser.currentToken()==JsonToken.VALUE_NUMBER_FLOAT || parser.currentToken()==JsonToken.VALUE_NUMBER_INT)out.writeNumber(parser.getText());
        else {require(parser.currentToken()!=null,"Truncated JSON value");out.copyCurrentEvent(parser);}
    }
    private static Resolution walk(JsonParser parser,String[] tokens,int depth) throws IOException {
        if(depth==tokens.length) {
            var bytes=new ByteArrayOutputStream();
            OutputStream capped=new FilterOutputStream(bytes){public void write(int b) throws IOException {if(bytes.size()>=MAX_DOCUMENT)throw new IOException("Selected JSON evidence exceeds 4MiB");out.write(b);}public void write(byte[] b,int off,int len) throws IOException {if(len>MAX_DOCUMENT-bytes.size())throw new IOException("Selected JSON evidence exceeds 4MiB");out.write(b,off,len);}};
            try(JsonGenerator generator=JSON.getFactory().createGenerator(capped)){copyValue(parser,generator);}
            JsonNode value=JSON.readTree(bytes.toByteArray());Resolution result=new Resolution(value);
            if(value.isObject()){Map<String,String> ids=new HashMap<>();for(String field:IDENTITY)if(value.path(field).isTextual())ids.put(field,value.path(field).asText());result.inherit(ids);}return result;
        }
        if(parser.currentToken()==JsonToken.START_OBJECT) {
            Map<String,String> ids=new HashMap<>();Resolution found=null;Set<String> seen=new HashSet<>();
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                require(parser.currentToken()==JsonToken.FIELD_NAME,"Malformed evidence object");String name=parser.currentName();require(seen.add(name),"Duplicate JSON field");parser.nextToken();
                if(name.equals(tokens[depth])){found=walk(parser,tokens,depth+1);if(IDENTITY.contains(name) && found!=null && found.value.isTextual())ids.put(name,found.value.asText());}
                else {if(IDENTITY.contains(name) && parser.currentToken()==JsonToken.VALUE_STRING)ids.put(name,parser.getText());parser.skipChildren();}
            }
            if(found!=null)found.inherit(ids);return found;
        }
        if(parser.currentToken()==JsonToken.START_ARRAY) {
            require(tokens[depth].matches("0|[1-9][0-9]*"),"Invalid array index");long requested;
            try{requested=Long.parseLong(tokens[depth]);}catch(NumberFormatException e){throw new IllegalArgumentException("Array index too large");}
            long index=0;Resolution found=null;
            while(parser.nextToken()!=JsonToken.END_ARRAY){if(index++==requested)found=walk(parser,tokens,depth+1);else parser.skipChildren();}return found;
        }
        return null;
    }
}
