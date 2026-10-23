package org.lokrusta.sqlprometheus.plan;

import org.apache.calcite.plan.RelOptLattice;
import org.apache.calcite.plan.RelOptMaterialization;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepMatchOrder;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.lokrusta.sqlprometheus.rel.PrometheusFilterRule;
import org.lokrusta.sqlprometheus.rel.PrometheusPushRules;
import org.lokrusta.sqlprometheus.rel.PrometheusToEnumerableRule;

import java.util.List;

public final class PrometheusPrograms {
    private PrometheusPrograms() {
    }

    public static HepProgram hepProgram() {
        return HepProgram.builder()
                .addMatchOrder(HepMatchOrder.BOTTOM_UP)
                .addGroupBegin()
                .addRuleInstance(PrometheusPushRules.PROJECT_FILTER)
                .addRuleInstance(PrometheusPushRules.JOIN_FILTER)
                .addRuleInstance(PrometheusFilterRule.INSTANCE)
                .addGroupEnd()
                .build();
    }

    public static Program create() {
        Program pushDown = Programs.of(
                hepProgram(),
                true,
                DefaultRelMetadataProvider.INSTANCE);
        Program registerConverter = (RelOptPlanner planner, RelNode rel,
                RelTraitSet requiredOutputTraits,
                List<RelOptMaterialization> materializations,
                List<RelOptLattice> lattices) -> {
            planner.addRule(PrometheusToEnumerableRule.INSTANCE);
            return rel;
        };
        return Programs.sequence(pushDown, registerConverter, Programs.standard());
    }
}
