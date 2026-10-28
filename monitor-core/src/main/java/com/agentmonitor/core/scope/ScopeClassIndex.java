package com.agentmonitor.core.scope;

import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.config.ScopeSelection;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable searchable class index. Rule evaluation is reused across browsing and text queries. */
public final class ScopeClassIndex {
    private final List<String> names, folded, packages;
    private final Map<String,List<String>> methods;
    private volatile Selection cached;
    public ScopeClassIndex(List<String> names,Map<String,List<String>> methods) {
        this.names=names.stream().distinct().sorted().toList();
        folded=this.names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        packages=this.names.stream().map(ScopeSelection::packageOf).filter(p -> !p.isBlank()).distinct().sorted().toList();
        Map<String,List<String>> copied=new HashMap<>();methods.forEach((name,values) -> copied.put(name,List.copyOf(values)));this.methods=Map.copyOf(copied);
    }
    public List<String> names(){return names;}
    public List<String> packages(){return packages;}
    public Selection select(MonitoringConfig.Scope rules,BooleanSupplier current) {
        Selection previous=cached;
        if(previous!=null && previous.rules.equals(rules)) return previous;
        ScopeSelection scope=new ScopeSelection(rules); BitSet selected=new BitSet(names.size()); long count=0;
        for(int i=0;i<names.size();i++) {
            if((i&255)==0) check(current);
            String name=names.get(i);
            if(scope.selected(name)) {
                selected.set(i);
                for(String method:methods.getOrDefault(name,List.of())) if(scope.methodSelected(name,method)) count++;
            }
        }
        check(current); Selection result=new Selection(this,rules,selected,count);cached=result;return result;
    }
    public Selection select(MonitoringConfig.Scope rules){return select(rules,() -> true);}
    public Query query(Selection selection,String packageName,String text,boolean selectedOnly,BooleanSupplier current) {
        if(selection.owner!=this)throw new IllegalArgumentException("Selection belongs to another index snapshot");
        int start=0,end=names.size();
        if(!packageName.isBlank()) {String prefix=packageName+".";start=lowerBound(prefix);end=lowerBound(prefix+'\uffff');}
        String needle=text.trim().toLowerCase(Locale.ROOT); List<String> matches=new ArrayList<>();
        int packageSelected=0,matchedSelected=0;
        for(int i=start;i<end;i++) {
            if((i&255)==0) check(current);
            boolean chosen=selection.selected.get(i);if(chosen)packageSelected++;
            if((!selectedOnly || chosen) && (needle.isEmpty() || folded.get(i).contains(needle))) {
                matches.add(names.get(i));if(chosen)matchedSelected++;
            }
        }
        check(current);return new Query(List.copyOf(matches),end-start,packageSelected,matchedSelected);
    }
    public Query query(Selection selection,String pkg,String text,boolean selectedOnly){return query(selection,pkg,text,selectedOnly,() -> true);}
    private int lowerBound(String value){int lo=0,hi=names.size();while(lo<hi){int mid=(lo+hi)>>>1;if(names.get(mid).compareTo(value)<0)lo=mid+1;else hi=mid;}return lo;}
    private static void check(BooleanSupplier current){if(Thread.currentThread().isInterrupted() || !current.getAsBoolean())throw new CancellationException("Outdated scope query");}
    public static final class Selection {
        private final ScopeClassIndex owner;
        private final MonitoringConfig.Scope rules; private final BitSet selected;private final long methods;
        private Selection(ScopeClassIndex owner,MonitoringConfig.Scope rules,BitSet selected,long methods){this.owner=owner;this.rules=rules;this.selected=(BitSet)selected.clone();this.methods=methods;}
        public long classCount(){return selected.cardinality();}public long methodCount(){return methods;}
    }
    public record Query(List<String> matches,int packageClassCount,int packageSelectedCount,int matchedSelectedCount){}
}
