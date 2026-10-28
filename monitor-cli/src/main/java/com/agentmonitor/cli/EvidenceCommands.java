package com.agentmonitor.cli;

import com.agentmonitor.core.evidence.*;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import static com.agentmonitor.cli.MonitorCli.*;

/** Small host adapter; indexing, source verification and temporal joins live in core. */
final class EvidenceCommands {
    static String help(){return """
              monitor evidence index --session <directory> [--json]
              monitor evidence query --session <directory> [--from <epochMillis>] [--to <epochMillis>] [--trace <id>] [--thread <id>] [--method <text>] [--kind span|metrics|threads|jfr|request_metrics] [--limit 50] [--offset 0] [--json]
              monitor evidence get --session <directory> --id <evidenceId> [--json]
              monitor timeline --session <directory> [--trace <id>] [--from <epochMillis>] [--to <epochMillis>] [--thread <id>] [--limit 100] [--json]
            """;}
    Integer run(String[] args,PrintWriter out)throws Exception {
        if(!Set.of("evidence","timeline").contains(args[0]))return null;
        boolean timeline=args[0].equals("timeline");if(!timeline&&args.length<2)throw new IllegalArgumentException(help());
        String action=timeline?"timeline":args[1];Set<String> allowed=switch(action){case "index"->Set.of("session");case "get"->Set.of("session","id");case "query"->Set.of("session","from","to","trace","thread","method","kind","limit","offset");case "timeline"->Set.of("session","from","to","trace","thread","limit");default->throw new IllegalArgumentException(help());};
        var opts=options(args,timeline?1:2,allowed);Path session=Path.of(required(opts,"session"));var store=new EvidenceStore();Object result=switch(action){
            case "index"->store.index(session);
            case "get"->store.get(session,required(opts,"id"));
            case "query"->store.query(session,new EvidenceStore.Query(number(opts,"from"),number(opts,"to"),opts.get("trace"),opts.get("thread"),opts.get("method"),opts.get("kind"),integer(opts,"limit",50),integer(opts,"offset",0)));
            case "timeline"->new TimelineService().query(session,number(opts,"from"),number(opts,"to"),opts.get("trace"),opts.get("thread"),integer(opts,"limit",100));
            default->throw new IllegalArgumentException(help());};emit(out,result);return OK;
    }
    private static Long number(Map<String,String> opts,String key){return opts.containsKey(key)?Long.parseLong(opts.get(key)):null;}
    private static int integer(Map<String,String> opts,String key,int fallback){return opts.containsKey(key)?Integer.parseInt(opts.get(key)):fallback;}
}
