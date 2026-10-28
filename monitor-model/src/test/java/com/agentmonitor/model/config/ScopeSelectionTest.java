package com.agentmonitor.model.config;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class ScopeSelectionTest {
    @Test void explicitPackageExclusionAndInheritancePreserveTheParentAndFutureRules() {
        var s=new ScopeSelection(new MonitoringConfig.Scope(List.of("example"),List.of(),List.of("cls:*DTO"),List.of()));
        s.excludePackage("example.internal");s.excludePackage("example.internal.generated");
        assertFalse(s.selected("example.internal.Future"));assertTrue(s.selected("example.api.Service"));
        assertTrue(s.packagePartial("example"));assertEquals("pkg:example.internal",s.packageExclusion("example.internal.api"));
        s.inheritPackage("example.internal");assertTrue(s.selected("example.internal.Future"));
        assertFalse(s.selected("example.internal.generated.Future"));assertFalse(s.selected("example.api.OrderDTO"));
        s.clearIncludes();s.excludePackage("another.internal");s.selectPackage("another",true);
        assertTrue(s.selected("another.internal.Future"));
    }
    @Test void selectingAClassDoesNotRequireSelectingItsPackage() {
        var s = new ScopeSelection(MonitoringConfig.Scope.defaults());
        assertEquals(ScopeSelection.Change.APPLIED, s.selectClass("example.Service", true));
        assertEquals(List.of("example.Service"), s.scope().includeClasses()); assertTrue(s.scope().includePackages().isEmpty());
        s.selectPackage("other", true); assertTrue(s.selected("example.Service"));
        s.selectClass("example.Service", false); assertFalse(s.selected("example.Service"));
    }
    @Test void removingASubpackageAlsoExcludesClassesLoadedInTheFuture() {
        var s = new ScopeSelection(new MonitoringConfig.Scope(List.of("example"), List.of(), List.of(), List.of()));
        s.selectPackage("example.internal", false);
        assertFalse(s.selected("example.internal.NotYetLoaded")); assertTrue(s.selected("example.api.Service"));
        assertEquals(List.of("pkg:example.internal"), s.scope().excludeConditions());
        s.selectPackage("example.internal", true); assertTrue(s.selected("example.internal.NotYetLoaded"));
    }
    @Test void wildcardAndAncestorExclusionsCannotBeOverriddenByFalseCheckboxes() {
        var s = new ScopeSelection(new MonitoringConfig.Scope(List.of("example"), List.of(), List.of("cls:*DTO", "pkg:example.internal"), List.of()));
        assertEquals(ScopeSelection.Change.EXCLUDED_BY_RULE, s.selectClass("example.OrderDTO", true));
        assertFalse(s.selected("example.OrderDTO"));
        var before = s.scope(); assertEquals(ScopeSelection.Change.EXCLUDED_BY_RULE, s.selectPackage("example.internal.api", true));
        assertEquals(before, s.scope());
    }
    @Test void clearAndRuleReplacementHaveOneTruthAndPreserveUnloadedConfiguration() {
        var scope = new MonitoringConfig.Scope(List.of("example"), List.of("unloaded.Future"), List.of("cls:example.Service"), List.of("get*"));
        var s = new ScopeSelection(scope); assertEquals(scope, s.copy().scope());
        assertFalse(s.selected("example.Service")); s.replaceRules(List.of(), List.of()); assertTrue(s.selected("example.Service"));
        s.clearIncludes(); assertTrue(s.scope().includePackages().isEmpty()); assertTrue(s.scope().includeClasses().isEmpty());
        assertFalse(s.selected("example.Service"));
    }    @Test void trailingPackageDotAndWildcardExclusionsMatchAgentContract() {
        for (String rule : List.of("example.","example.*","**.many.**")) {
            var scope=new ScopeSelection(new MonitoringConfig.Scope(List.of("example"),List.of(),List.of("pkg:"+rule),List.of()));
            for(String cls:List.of("example.Service","example.many.Service","example.other.Item"))
                assertEquals(PackagePatternMatcher.matchesClassName(rule,cls),!scope.exclusion(cls).isEmpty(),rule+" / "+cls);
        }
    }
    @Test void compiledMethodRulesPreserveAliasesAndInvalidateWhenRulesAreReplaced() {
        var selection=new ScopeSelection(new MonitoringConfig.Scope(List.of("example"),List.of(),List.of(),List.of("get.*","example.Service.find*")));
        assertFalse(selection.methodSelected("example.Service","getName"));assertFalse(selection.methodSelected("example.Service","findOne"));
        assertTrue(selection.methodSelected("other.Service","findOne"));assertTrue(selection.methodSelected("example.Service","work"));
        selection.replaceRules(List.of(),List.of("work"));assertTrue(selection.methodSelected("example.Service","getName"));assertFalse(selection.methodSelected("example.Service","work"));
    }

}
