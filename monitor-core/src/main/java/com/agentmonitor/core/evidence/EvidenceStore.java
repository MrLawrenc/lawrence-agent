package com.agentmonitor.core.evidence;

import com.agentmonitor.core.util.AtomicFiles;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded, rebuildable evidence index. Values are always resolved and verified against source artifacts. */
public final class EvidenceStore {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).maxStringLength(1_048_576).build()).build()).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final long MAX_SOURCE=128L*1024*1024,MAX_INDEX=64L*1024*1024;
    private static final int MAX_RECORDS=200000;
    public record Query(Long fromMillis,Long toMillis,String traceId,String threadId,String method,String kind,int limit,int offset) {
        public Query { if(limit<1||limit>200||offset<0||fromMillis!=null&&toMillis!=null&&fromMillis>toMillis)throw new IllegalArgumentException("Invalid evidence range/limit/offset"); }
    }
    private static final class IdentityMismatch extends IllegalArgumentException {
        IdentityMismatch(String message){super(message);}
    }
    public Map<String,Object> index(Path selected) throws Exception {
        Path root=selected.toRealPath();String id=identity(root);Path directory=root.resolve("evidence");Files.createDirectories(directory);
        String filename="index-"+UUID.randomUUID()+".jsonl";Path output=directory.resolve(filename);
        List<Map<String,Object>> sources=new ArrayList<>();List<String> errors=new ArrayList<>();
        int[] records={0};long[] written={0},processed={0};boolean[] truncated={false};List<Path> files=new ArrayList<>();
        for(String folder:List.of("diagnostics","spans")){
            Path parent=root.resolve(folder);if(!Files.isDirectory(parent))continue;
            try(var stream=Files.list(parent)){
                var candidates=stream.filter(f->Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(f))
                    .filter(f->f.getFileName().toString().matches(".*\\.(json|jsonl|json\\.gz|jsonl\\.gz)")).sorted().limit(4097).toList();
                if(candidates.size()>4096)truncated[0]=true;files.addAll(candidates.subList(0,Math.min(4096,candidates.size())));
            }
        }
        Path live=root.resolve("agent-logs/live-invocations.json");if(Files.isRegularFile(live,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(live))files.add(live);
        Path requests=root.resolve("agent-logs/request-metrics.json");if(Files.isRegularFile(requests,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(requests))files.add(requests);
        try(BufferedWriter writer=Files.newBufferedWriter(output,StandardOpenOption.CREATE_NEW)){
            for(Path selectedFile:files){
                if(records[0]>=MAX_RECORDS||written[0]>=MAX_INDEX||processed[0]>=2L*1024*1024*1024){truncated[0]=true;break;}
                Path file=safe(root,selectedFile);long size=Files.size(file),modified=Files.getLastModifiedTime(file).toMillis();
                String relative=root.relativize(file).toString().replace('\\','/');
                if(size>MAX_SOURCE){errors.add(relative+": exceeds 128MiB");truncated[0]=true;continue;}
                boolean jsonl=relative.endsWith(".jsonl")||relative.endsWith(".jsonl.gz");String hash=sha(file);
                Path staging=Files.createTempFile(directory,"source-",".tmp");int before=records[0];long bytesBefore=written[0];boolean valid=true;
                try(BufferedWriter stage=Files.newBufferedWriter(staging);InputStream input=open(file);JsonParser parser=JSON.getFactory().createParser(input)){
                    int documents=0;JsonToken token;
                    while((token=parser.nextToken())!=null){
                        if(!jsonl&&documents++>0)throw new IOException("Multiple source JSON documents");
                        if(records[0]>=MAX_RECORDS||written[0]>=MAX_INDEX){truncated[0]=true;parser.skipChildren();continue;}
                        if(jsonl){
                            long line=parser.currentTokenLocation().getLineNr();JsonNode row=readRecord(parser);checkIdentity(id,row);add(stage,id,relative,line,"",row,records,written);
                            if(row.path("threads").isArray()){int t=0;for(JsonNode thread:row.path("threads")){if(records[0]>=MAX_RECORDS||written[0]>=MAX_INDEX){truncated[0]=true;break;}add(stage,id,relative,line,"/threads/"+(t++),thread,records,written,row.path("timestampMillis").asLong());}}
                        }else if(relative.equals("agent-logs/request-metrics.json")&&token==JsonToken.START_OBJECT){
                            JsonNode doc=readRecord(parser);checkIdentity(id,doc);int endpoint=0;
                            for(JsonNode row:doc.path("endpoints")){
                                if(records[0]>=MAX_RECORDS||written[0]>=MAX_INDEX){truncated[0]=true;break;}
                                add(stage,id,relative,0,"/endpoints/"+(endpoint++),row,records,written,doc.path("startedAtMillis").asLong(),doc.path("timestampMillis").asLong());
                            }
                        }else if(token==JsonToken.START_OBJECT){
                            scanObject(parser,"",(pointer,row)->add(stage,id,relative,0,pointer,row,records,written),records,written,truncated,id);
                        }else if(token==JsonToken.START_ARRAY){
                            int number=0;while(parser.nextToken()!=JsonToken.END_ARRAY){
                                if(records[0]>=MAX_RECORDS||written[0]>=MAX_INDEX){truncated[0]=true;parser.skipChildren();continue;}
                                add(stage,id,relative,0,"/"+(number++),readRecord(parser),records,written);
                            }
                        }else throw new IOException("Source must contain objects/arrays");
                    }
                    processed[0]+=Math.max(size,parser.currentLocation().getByteOffset());
                }catch(IdentityMismatch mismatch){Files.deleteIfExists(staging);throw mismatch;}
                catch(Exception error){valid=false;records[0]=before;written[0]=bytesBefore;errors.add(relative+": "+error.getMessage());truncated[0]=true;}
                try{
                    if(size!=Files.size(file)||modified!=Files.getLastModifiedTime(file).toMillis()||!hash.equals(sha(file)))throw new IOException("Source changed while indexing: "+relative);
                    if(valid){try(var reader=Files.newBufferedReader(staging)){reader.transferTo(writer);}sources.add(Map.of("file",relative,"sha256",hash,"bytes",size,"modifiedMillis",modified));}
                }finally{Files.deleteIfExists(staging);}
            }
        }catch(Exception failure){Files.deleteIfExists(output);throw failure;}
        Map<String,Object> catalog=new LinkedHashMap<>();catalog.put("schemaVersion",1);catalog.put("sessionId",id);catalog.put("index",filename);
        catalog.put("indexSha256",sha(output));catalog.put("createdAtMillis",System.currentTimeMillis());catalog.put("identityValidated",true);catalog.put("records",records[0]);catalog.put("truncated",truncated[0]||records[0]>=MAX_RECORDS||written[0]>=MAX_INDEX);catalog.put("errors",errors);catalog.put("sources",sources);
        catalog.put("limits",Map.of("records",MAX_RECORDS,"indexBytes",MAX_INDEX,"sourceBytes",MAX_SOURCE,"totalSourceBytes",2L*1024*1024*1024));
        AtomicFiles.write(directory.resolve("catalog.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(catalog));return Collections.unmodifiableMap(catalog);
    }
    private static void checkIdentity(String expected,JsonNode node){
        if(node.has("sessionId")&&(!node.path("sessionId").isTextual()||!expected.equals(node.path("sessionId").asText())))throw new IdentityMismatch("Source belongs to another session");
    }
    @FunctionalInterface private interface Row {void accept(String pointer,JsonNode node)throws Exception;}
    private static void scanObject(JsonParser parser,String base,Row row,int[] records,long[] written,boolean[] truncated,String id)throws Exception {
        while(parser.nextToken()!=JsonToken.END_OBJECT){if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new IOException("Malformed source object");String field=parser.currentName();parser.nextToken();
            if(field.equals("sessionId")){if(parser.currentToken()!=JsonToken.VALUE_STRING||!id.equals(parser.getText()))throw new IdentityMismatch("Container/session mismatch");}
            else if(Set.of("spans","roots","timelineEvents","endpoints").contains(field)&&parser.currentToken()==JsonToken.START_ARRAY){int index=0;while(parser.nextToken()!=JsonToken.END_ARRAY){if(records[0]>=MAX_RECORDS||written[0]>=MAX_INDEX){truncated[0]=true;parser.skipChildren();continue;}row.accept(base+"/"+field+"/"+(index++),readRecord(parser));}}
            else parser.skipChildren();
        }
    }
    private static JsonNode readRecord(JsonParser parser)throws Exception {long start=parser.currentTokenLocation().getByteOffset();JsonNode value=JSON.readTree(parser);if(parser.currentLocation().getByteOffset()-start>4L*1024*1024)throw new IOException("Evidence record exceeds 4MiB");return value;}
    private static void add(BufferedWriter writer,String id,String file,long line,String pointer,JsonNode value,int[] records,long[] bytes)throws Exception {
        add(writer,id,file,line,pointer,value,records,bytes,0);
    }
    private static void add(BufferedWriter writer,String id,String file,long line,String pointer,JsonNode value,int[] records,long[] bytes,long fallbackStart)throws Exception {
        add(writer,id,file,line,pointer,value,records,bytes,fallbackStart,0);
    }
    private static void add(BufferedWriter writer,String id,String file,long line,String pointer,JsonNode value,int[] records,long[] bytes,long fallbackStart,long fallbackEnd)throws Exception {
        if(!value.isObject()||records[0]>=MAX_RECORDS||bytes[0]>=MAX_INDEX)return;
        checkIdentity(id,value);
        String trace=value.path("traceId").asText(),method=value.path("method").asText(value.path("rootMethod").asText());
        if(method.isBlank()&&value.has("className"))method=value.path("className").asText()+"#"+value.path("methodName").asText()+value.path("signature").asText()+" ["+value.path("kind").asText("BUSINESS")+"]";
        JsonNode attrs=value.path("attributes");long start=value.path("timestampMillis").asLong(value.path("startedAtEpochMillis").asLong(value.path("startedAtMillis").asLong(fallbackStart)));
        long duration=value.path("durationNanos").asLong(value.path("elapsedMillis").asLong()*1_000_000L);try{if(attrs.has("agent.span.elapsed_nanos"))duration=Long.parseLong(attrs.path("agent.span.elapsed_nanos").asText());}catch(NumberFormatException ignored){}long end=fallbackEnd>0?fallbackEnd:start+Math.max(0,duration/1_000_000);
        String thread=value.path("javaThreadId").asText(value.path("threadId").asText(attrs.path("thread.id").asText()));
        String recordHash=shaBytes(JSON.writeValueAsBytes(value));String evidenceId=shaBytes((id+"\n"+file+"\n"+line+"\n"+pointer+"\n"+recordHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String,Object> row=new LinkedHashMap<>();row.put("evidenceId",evidenceId);row.put("sessionId",id);row.put("file",file);row.put("line",line);row.put("jsonPointer",pointer);row.put("startedAtMillis",start);row.put("endedAtMillis",end);row.put("traceId",trace);row.put("threadId",thread);row.put("threadName",value.path("threadName").asText(value.path("thread").asText()));row.put("method",method);row.put("eventType",value.path("eventType").asText(value.path("event").asText()));row.put("kind",file.contains("metrics")?"metrics":file.contains("threads")?"threads":file.contains("jfr")?"jfr":trace.isBlank()?"diagnostic":"span");row.put("recordSha256",recordHash);
        if(trace.length()>256||method.length()>8192||thread.length()>64||row.get("threadName").toString().length()>512)throw new IOException("Evidence metadata exceeds bounded query limits");
        if(file.equals("agent-logs/request-metrics.json")){String endpoint=value.path("endpoint").asText();if(endpoint.length()>8192)throw new IOException("Endpoint metadata exceeds bounded query limits");row.put("endpoint",endpoint);row.put("kind","request_metrics");row.put("aggregationScope","capture_window_not_individual_request");}
        String encoded=JSON.writeValueAsString(row);long length=encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1;
        if(length>MAX_INDEX-bytes[0]){bytes[0]=MAX_INDEX;return;}writer.write(encoded);writer.newLine();bytes[0]+=length;records[0]++;
    }
    public Map<String,Object> query(Path selected,Query query)throws Exception {
        Path root=selected.toRealPath();JsonNode catalog=catalogOrBuild(root);List<Map<String,Object>> matches=new ArrayList<>();long[] count={0};
        rows(root,catalog,node->{if(!matches(node,query))return;long ordinal=count[0]++;if(ordinal<query.offset()||matches.size()>=query.limit())return;matches.add(JSON.convertValue(node,Map.class));});
        return Map.of("sessionId",identity(root),"matches",matches,"matchedRecords",count[0],"offset",query.offset(),"limit",query.limit(),"truncatedIndex",catalog.path("truncated").asBoolean(),"indexErrors",JSON.convertValue(catalog.path("errors"),List.class),"valuesVerified",false,"note","Index metadata only; evidence get / AI verification checks original file hashes and values.");
    }
    public List<Map<String,Object>> timelineRows(Path selected,Query query)throws Exception {
        Path root=selected.toRealPath();JsonNode catalog=catalogOrBuild(root);List<Map<String,Object>> result=new ArrayList<>();rows(root,catalog,n->{if(result.size()<5000&&matches(n,query))result.add(JSON.convertValue(n,Map.class));});return List.copyOf(result);
    }
    public void verifySources(Path selected,Set<String> sources)throws Exception {Path root=selected.toRealPath();JsonNode doc=catalog(root);for(String source:sources)verifySource(root,doc,source);}
    private static boolean matches(JsonNode n,Query q){
        return (q.fromMillis()==null||n.path("endedAtMillis").asLong()>=q.fromMillis())&&(q.toMillis()==null||n.path("startedAtMillis").asLong()<=q.toMillis())
                &&contains(n,"traceId",q.traceId(),false)&&contains(n,"threadId",q.threadId(),false)&&(contains(n,"method",q.method(),true)||n.path("kind").asText().equals("request_metrics")&&contains(n,"endpoint",q.method(),true))&&contains(n,"kind",q.kind(),false);
    }
    private static boolean contains(JsonNode n,String field,String filter,boolean substring){return filter==null||filter.isBlank()||(substring?n.path(field).asText().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)):n.path(field).asText().equals(filter));}
    public Map<String,Object> get(Path selected,String evidenceId)throws Exception {
        Path root=selected.toRealPath();JsonNode catalog=catalog(root);JsonNode[] found={null};rows(root,catalog,node->{if(node.path("evidenceId").asText().equals(evidenceId))found[0]=node;});
        if(found[0]==null)throw new IllegalArgumentException("Unknown evidenceId");JsonNode row=found[0];Path file=safe(root,root.resolve(row.path("file").asText()));
        verifySource(root,catalog,row.path("file").asText());JsonNode value=readAt(file,row.path("line").asLong(),row.path("jsonPointer").asText());checkIdentity(identity(root),value);if(row.path("line").asLong()>0)checkIdentity(identity(root),document(file,row.path("line").asLong()));
        if(value.isMissingNode()||!shaBytes(JSON.writeValueAsBytes(value)).equals(row.path("recordSha256").asText()))throw new IllegalArgumentException("Indexed evidence changed");
        Map<String,Object> result=new LinkedHashMap<>(JSON.convertValue(row,Map.class));result.put("value",JSON.convertValue(value,Object.class));result.put("verified",true);return result;
    }
    public void verifyReference(Path selected,String evidenceId,String file,long line,String pointer)throws Exception {
        Map<String,Object> source=get(selected,evidenceId);String base=source.get("jsonPointer").toString();
        if(!source.get("file").equals(file.replace('\\','/'))||((Number)source.get("line")).longValue()!=line||!(pointer.equals(base)||pointer.startsWith(base+"/")))throw new IllegalArgumentException("Evidence ID does not match citation file/line/pointer");
    }
    public static byte[] recordBytes(Path file,long line)throws Exception {return JSON.writeValueAsBytes(document(file,line));}
    private static JsonNode readAt(Path file,long line,String pointer)throws Exception {
        if(line>0)return document(file,line).at(pointer);
        if(!pointer.isEmpty()&&(!pointer.startsWith("/")||java.util.regex.Pattern.compile("~(?![01])").matcher(pointer).find()))throw new IllegalArgumentException("Invalid JSON Pointer");
        String[] parts=pointer.isEmpty()?new String[0]:Arrays.stream(pointer.substring(1).split("/",-1)).map(p->p.replace("~1","/").replace("~0","~")).toArray(String[]::new);
        try(InputStream input=open(file);JsonParser parser=JSON.getFactory().createParser(input)){if(parser.nextToken()==null)throw new IOException("Empty JSON");JsonNode result=select(parser,parts,0);if(parser.nextToken()!=null)throw new IOException("Multiple JSON documents");if(result==null)throw new IOException("Evidence pointer missing");return result;}
    }
    private static JsonNode select(JsonParser parser,String[] parts,int depth)throws Exception {
        if(depth==parts.length)return readRecord(parser);
        JsonNode result=null;
        if(parser.currentToken()==JsonToken.START_OBJECT){while(parser.nextToken()!=JsonToken.END_OBJECT){String field=parser.currentName();parser.nextToken();if(field.equals(parts[depth]))result=select(parser,parts,depth+1);else parser.skipChildren();}}
        else if(parser.currentToken()==JsonToken.START_ARRAY){if(!parts[depth].matches("0|[1-9][0-9]*"))throw new IOException("Invalid array index");long wanted=Long.parseLong(parts[depth]),i=0;while(parser.nextToken()!=JsonToken.END_ARRAY){if(i++==wanted)result=select(parser,parts,depth+1);else parser.skipChildren();}}
        else parser.skipChildren();return result;
    }
    private static JsonNode document(Path file,long line)throws Exception {
        boolean jsonl=file.toString().matches(".*\\.jsonl(\\.gz)?");if(jsonl!=(line>0))throw new IllegalArgumentException("JSONL requires a positive line; JSON must not specify a line");
        try(InputStream input=open(file);JsonParser parser=JSON.getFactory().createParser(input)){
            if(jsonl){while(parser.nextToken()!=null){long number=parser.currentTokenLocation().getLineNr();if(number==line)return readRecord(parser);parser.skipChildren();}throw new IllegalArgumentException("JSONL line missing");}
            if(parser.nextToken()==null)throw new IllegalArgumentException("Empty JSON");JsonNode value=readRecord(parser);if(parser.nextToken()!=null)throw new IllegalArgumentException("Multiple JSON documents");return value;
        }
    }
    private JsonNode catalogOrBuild(Path root)throws Exception {if(!Files.isRegularFile(root.resolve("evidence/catalog.json")))index(root);return catalog(root);}
    private JsonNode catalog(Path root)throws Exception {Path path=root.resolve("evidence/catalog.json");if(!Files.isRegularFile(path))throw new IllegalArgumentException("Evidence index missing; run evidence index first");if(Files.size(path)>4L*1024*1024)throw new IOException("Catalog too large");JsonNode doc=JSON.readTree(safe(root,path).toFile());if(!doc.path("identityValidated").isBoolean()||!doc.path("identityValidated").asBoolean())throw new IllegalArgumentException("Old evidence index must be rebuilt");if(!identity(root).equals(doc.path("sessionId").asText()))throw new IllegalArgumentException("Index/session mismatch");return doc;}
    @FunctionalInterface private interface Visitor {void visit(JsonNode node)throws Exception;}
    private static void rows(Path root,JsonNode catalog,Visitor visitor)throws Exception {Path file=safe(root,root.resolve("evidence").resolve(catalog.path("index").asText()));if(Files.size(file)>MAX_INDEX+65536||!sha(file).equals(catalog.path("indexSha256").asText()))throw new IOException("Index integrity check failed");try(JsonParser parser=JSON.getFactory().createParser(file.toFile())){while(parser.nextToken()!=null)visitor.visit(readRecord(parser));}}
    private static void verifySource(Path root,JsonNode catalog,String relative)throws Exception {for(JsonNode source:catalog.path("sources"))if(source.path("file").asText().equals(relative)){if(!sha(safe(root,root.resolve(relative))).equals(source.path("sha256").asText()))throw new IllegalArgumentException("Source hash changed; rebuild index");return;}throw new IllegalArgumentException("Source absent from catalog");}
    private static String identity(Path root)throws Exception {Path p=safe(root,root.resolve("session.json"));if(Files.size(p)>65536)throw new IOException("Session marker too large");JsonNode n=JSON.readTree(p.toFile());String id=n.path("sessionId").asText();if(id.isBlank())throw new IllegalArgumentException("Missing session identity");return id;}
    private static Path safe(Path root,Path file)throws IOException {Path actual=file.toRealPath();if(!actual.startsWith(root)||!Files.isRegularFile(actual))throw new IllegalArgumentException("Evidence path escapes session or is not a file");return actual;}
    private static InputStream open(Path file)throws IOException {InputStream raw=Files.newInputStream(file);try{return limited(file.toString().endsWith(".gz")?new GZIPInputStream(raw):raw,MAX_SOURCE);}catch(IOException e){raw.close();throw e;}}
    private static InputStream limited(InputStream source,long maximum){return new FilterInputStream(source){long count;private void add(long n)throws IOException{if(n>0&&(count+=n)>maximum)throw new IOException("Evidence source exceeds 128MiB decompressed");}public int read()throws IOException{int b=in.read();if(b>=0)add(1);return b;}public int read(byte[] b,int o,int l)throws IOException{int n=in.read(b,o,l);add(n);return n;}};}
    private static String sha(Path file)throws Exception {if(Files.size(file)>MAX_SOURCE)throw new IOException("Source exceeds 128MiB");MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());}
    private static String shaBytes(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
}
