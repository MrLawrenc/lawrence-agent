package dev.perflab;

import dev.perflab.work.LabWork;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.Callable;

@RestController
public class LabController {
    private final LabWork work;
    public LabController(LabWork work){this.work=work;}
    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","UP","pid",ProcessHandle.current().pid());}
    @GetMapping("/chain") public Map<String,Object> chain(HttpServletRequest request)throws Exception{return work.chain(request.getHeader("traceparent"));}
    @GetMapping("/items/{id}") public Map<String,Object> item(@PathVariable("id") String id){return Map.of("id",id);}
    @GetMapping("/slow-sql") public Map<String,Object> sql(){return work.sql();}
    @GetMapping("/pool-wait") public Map<String,Object> pool()throws Exception{return work.poolWait();}
    @GetMapping("/redis") public Map<String,Object> redis(){return work.redis();}
    @GetMapping("/rabbit") public Map<String,Object> rabbit()throws Exception{return work.rabbit();}
    @GetMapping("/apache") public Map<String,Object> apache()throws Exception{return work.apache();}
    @GetMapping("/cpu") public Map<String,Object> cpu(){return Map.of("checksum",work.cpu());}
    @GetMapping("/memory") public Map<String,Object> memory(){return work.memory();}
    @GetMapping("/async") public Callable<Map<String,Object>> async(){return ()->{Thread.sleep(240);return Map.of("completed",true);};}
    @PostMapping(value="/echo",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE,produces=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] echo(@RequestBody byte[] body){return body;}
    @GetMapping("/failure") public ResponseEntity<Map<String,Object>> failure(){return ResponseEntity.status(503).body(Map.of("fixtureFailure",true));}
}
