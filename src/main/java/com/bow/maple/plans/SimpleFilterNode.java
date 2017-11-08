package com.bow.maple.plans;

import java.io.IOException;
import java.util.ArrayList;

import com.bow.maple.expressions.Expression;
import com.bow.maple.qeval.ColumnStats;
import com.bow.maple.qeval.PlanCost;
import com.bow.maple.qeval.SelectivityEstimator;

/**
 * 通过{@link SelectNode#getNextTuple()}进行过滤<br/>
 * This select plan node implements a simple filter of a subplan based on a
 * predicate.
 */
public class SimpleFilterNode extends SelectNode {

    /**
     * WHERE 语句对应的node
     * 
     * @param child 此node只有left child一个子节点
     * @param predicate WHERE的过滤条件
     */
    public SimpleFilterNode(PlanNode child, Expression predicate) {
        super(child, predicate);
    }



    @Override
    public void prepare() {
        leftChild.prepare();

        // 获取schema & stats
        schema = leftChild.getSchema();
        ArrayList<ColumnStats> childStats = leftChild.getStats();

        // 计算出基于predicate的选择率
        float selectivity = 1.0f;
        if (predicate != null) {
            selectivity = SelectivityEstimator.estimateSelectivity(predicate, schema, childStats);
        }

        // 根据子节点的cost和选择率来计算本层的cost
        PlanCost childCost = leftChild.getCost();
        cost = new PlanCost(childCost);
        cost.numTuples *= selectivity;

        // The CPU cost will be proportional to the total number of tuples, not
        // the number of tuples we expect to output.
        cost.cpuCost += childCost.numTuples;

        // TODO: We should update the table statistics, but for now we'll just
        // use the child's stats unmodified.

        stats = childStats;
    }

    @Override
    public void initialize() {
        super.initialize();
        leftChild.initialize();
    }

    @Override
    public void cleanUp() {
        leftChild.cleanUp();
    }

    @Override
    protected void advanceCurrentTuple() throws IOException {
        currentTuple = leftChild.getNextTuple();
    }

    /**
     * Returns true if the passed-in object is a <tt>SimpleFilterNode</tt> with
     * the same predicate and child sub-expression.
     *
     * @param obj the object to check for equality
     *
     * @return true if the passed-in object is equal to this object; false
     *         otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof SimpleFilterNode) {
            SimpleFilterNode other = (SimpleFilterNode) obj;
            return leftChild.equals(other.leftChild) && predicate.equals(other.predicate);
        }
        return false;
    }

    /**
     * Computes the hashcode of a PlanNode. This method is used to see if two
     * plan nodes CAN be equal.
     **/
    @Override
    public int hashCode() {
        int hash = 7;
        hash = 31 * hash + (predicate != null ? predicate.hashCode() : 0);
        hash = 31 * hash + leftChild.hashCode();
        return hash;
    }

    /**
     * Creates a copy of this simple filter node node and its subtree. This
     * method is used by {@link PlanNode#duplicate} to copy a plan tree.
     */
    @Override
    protected PlanNode clone() throws CloneNotSupportedException {
        SimpleFilterNode node = (SimpleFilterNode) super.clone();

        // Copy the subtree.
        node.leftChild = leftChild.duplicate();

        return node;
    }

    @Override
    public String toString() {
        return "SimpleFilter[pred:  " + predicate.toString() + "]";
    }
}
