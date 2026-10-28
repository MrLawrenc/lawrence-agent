package com.agentmonitor.core.scope;
import com.agentmonitor.model.config.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
class ScopeClassIndexTest {
    @Test void packageCountsRemainIndependentOfTextAndSelectedOnlyFilters() {
        var names=List.of("a.Service","a.DTO","a.internal.Hidden","ab.Service","z.Outside");
        var rules=new MonitoringConfig.Scope(List.of("a"),List.of("z.Outside"),List.of("cls:*DTO"),List.of("get*"));
        var index=new ScopeClassIndex(names,Map.of("a.Service",List.of("getName","work")));
        var selected=index.select(rules);assertEquals(3,selected.classCount());assertEquals(1,selected.methodCount());
        var query=index.query(selected,"a","Service",false);
        assertEquals(List.of("a.Service"),query.matches());assertEquals(3,query.packageClassCount());assertEquals(2,query.packageSelectedCount());assertEquals(1,query.matchedSelectedCount());
        assertEquals(2,index.query(selected,"a","",true).matches().size());
        assertSame(selected,index.select(rules),"Browsing reuses the unchanged global rule projection");
        var cleared=index.select(new MonitoringConfig.Scope(List.of(),List.of(),List.of(),List.of()));assertEquals(0,cleared.classCount());
        assertEquals(3,selected.classCount(),"Published projections do not mutate");
    }
    @Test void optimizedQueriesMatchTheSimpleContractAcrossRulesAndFilters() {
        var names=List.of("a.Service","a.ModelDTO","a.deep.Worker","a.deep.lower.Item","ab.Item","a$Root","z.Service");
        var index=new ScopeClassIndex(names,Map.of());
        for(var rules:List.of(new MonitoringConfig.Scope(List.of("a."),List.of(),List.of("pkg:a.deep.lower"),List.of()),
                new MonitoringConfig.Scope(List.of("a"),List.of("z.Service"),List.of("cls:*DTO","pkg:**.lower.**"),List.of()))) {
            var selection=index.select(rules);var simple=new ScopeSelection(rules);
            for(String pkg:List.of("","a","a.deep","missing"))for(String text:List.of("","SERVICE","item"))for(boolean only:List.of(true,false)) {
                var expected=index.names().stream().filter(n -> pkg.isEmpty() || ScopeSelection.under(ScopeSelection.packageOf(n),pkg))
                    .filter(n -> n.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))).filter(n -> !only || simple.selected(n)).toList();
                assertEquals(expected,index.query(selection,pkg,text,only).matches());
            }
        }
    }
    @Test void cancelledWorkCannotPublishOrPoisonCachedResults() {
        var index=new ScopeClassIndex(List.of("a.A"),Map.of());var rules=new MonitoringConfig.Scope(List.of("a"),List.of(),List.of(),List.of());
        assertThrows(CancellationException.class,() -> index.select(rules,() -> false));
        var selected=index.select(rules);assertEquals(1,selected.classCount());
        var other=new ScopeClassIndex(List.of("other.B"),Map.of());
        assertThrows(IllegalArgumentException.class,() -> other.query(selected,"","",false));
        assertThrows(CancellationException.class,() -> index.query(selected,"a","",false,() -> false));
    }
}
