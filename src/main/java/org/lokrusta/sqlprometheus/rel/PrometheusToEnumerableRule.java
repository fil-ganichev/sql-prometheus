package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;

public class PrometheusToEnumerableRule extends ConverterRule {
    public static final Config DEFAULT_CONFIG = Config.INSTANCE
            .withConversion(
                    PrometheusTableScan.class,
                    Convention.NONE,
                    EnumerableConvention.INSTANCE,
                    "PrometheusToEnumerableRule")
            .withRuleFactory(PrometheusToEnumerableRule::new);

    public static final PrometheusToEnumerableRule INSTANCE =
            DEFAULT_CONFIG.toRule(PrometheusToEnumerableRule.class);

    protected PrometheusToEnumerableRule(Config config) {
        super(config);
    }

    @Override
    public RelNode convert(RelNode rel) {
        return PrometheusEnumerableScan.create((PrometheusTableScan) rel);
    }
}
