package com.agentmonitor.agent.interceptor;

import java.util.*;

/** Bounded W3C Trace Context parsing. Never accepts zero IDs or multiple traceparent values. */
public record DistributedContext(String traceId, String parentId, int flags, String traceState) {
    public static DistributedContext parse(String parent, String state) {
        if (parent == null || parent.length() < 55 || parent.length() > 512) return null;
        if (!parent.substring(0,55).matches("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) return null;
        String version=parent.substring(0,2);
        if(version.equals("ff") || version.equals("00") && parent.length()!=55
                || parent.length()>55 && (parent.charAt(55)!='-' || parent.length()==56)
                || parent.substring(3,35).equals("0".repeat(32)) || parent.substring(36,52).equals("0".repeat(16))) return null;
        return new DistributedContext(parent.substring(3,35),parent.substring(36,52),Integer.parseInt(parent.substring(53,55),16)&1,validState(state));
    }
    public static String validState(String text) {
        if(text==null || text.isBlank() || text.length()>512) return "";
        String[] members=text.split(",",-1); if(members.length>32)return "";
        Set<String> keys=new HashSet<>();
        for(String member:members){String pair=member.strip();int equals=pair.indexOf('=');if(equals<1)return "";
            String key=pair.substring(0,equals),value=pair.substring(equals+1);
            if(key.length()>256 || !key.matches("[a-z][a-z0-9_*/-]{0,255}|[a-z0-9][a-z0-9_*/-]{0,240}@[a-z][a-z0-9_*/-]{0,13}")
                    || !keys.add(key) || value.isEmpty() || value.length()>256 || value.endsWith(" "))return "";
            for(char c:value.toCharArray())if(c<32 || c>126 || c==',' || c=='=')return "";
        }
        return text;
    }
    public static String header(SpanContext span) {
        return "00-"+span.traceId+"-"+span.spanId+"-"+(span.traceSampling.isHeadSampled()?"01":"00");
    }
}
