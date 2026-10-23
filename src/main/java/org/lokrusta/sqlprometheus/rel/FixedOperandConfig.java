package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.tools.RelBuilderFactory;

/**
 * Правило с одним фиксированным шаблоном операндов.
 * Заменяет deprecated {@code RelOptRule.operand}/{@code none}/{@code any}.
 */
abstract class FixedOperandConfig implements RelRule.Config {
    private final String description;
    private final RelRule.OperandTransform operandSupplier;

    FixedOperandConfig(String description, RelRule.OperandTransform operandSupplier) {
        this.description = description;
        this.operandSupplier = operandSupplier;
    }

    @Override
    public final String description() {
        return description;
    }

    @Override
    public final RelRule.OperandTransform operandSupplier() {
        return operandSupplier;
    }

    @Override
    public RelRule.Config withRelBuilderFactory(RelBuilderFactory factory) {
        return this;
    }

    @Override
    public RelRule.Config withDescription(String description) {
        return this;
    }

    @Override
    public RelRule.Config withOperandSupplier(RelRule.OperandTransform operandSupplier) {
        return this;
    }

    @Override
    public abstract RelOptRule toRule();
}
