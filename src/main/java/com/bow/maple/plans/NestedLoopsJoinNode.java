package com.bow.maple.plans;

import com.bow.maple.expressions.Expression;
import com.bow.maple.qeval.ColumnStats;
import com.bow.maple.qeval.SelectivityEstimator;
import com.bow.maple.relations.Tuple;
import com.bow.maple.expressions.OrderByExpression;
import com.bow.maple.qeval.PlanCost;
import com.bow.maple.relations.JoinType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This plan node implements a nested-loops join operation, which can support
 * arbitrary join conditions but is also the slowest join implementation.
 */
public class NestedLoopsJoinNode extends ThetaJoinNode {

    private Tuple leftTuple;

    private Tuple rightTuple;

    private boolean done;

    public NestedLoopsJoinNode(PlanNode leftChild, PlanNode rightChild, JoinType joinType, Expression predicate) {
        super(leftChild, rightChild, joinType, predicate);
    }

    /**
     * Nested-loop joins can conceivably produce sorted results in situations
     * where the outer relation is ordered, but we will keep it simple and just
     * report that the results are not ordered.
     */
    public List<OrderByExpression> resultsOrderedBy() {
        return null;
    }

    @Override
    public void prepare() {
        // Need to prepare the left and right child-nodes before we can do
        // our own work.
        leftChild.prepare();
        rightChild.prepare();

        // Use the parent class' helper-function to prepare the schema.
        prepareSchemaStats();

        PlanCost leftCost = leftChild.getCost();
        ArrayList<ColumnStats> leftStats = leftChild.getStats();

        PlanCost rightCost = rightChild.getCost();
        ArrayList<ColumnStats> rightStats = rightChild.getStats();

        float selectivity = 1.0f;
        if (predicate != null) {
            selectivity = SelectivityEstimator.estimateSelectivity(predicate, schema, stats);
        }

        if (leftCost != null && rightCost != null) {
            // Number of tuples in the plain cartesian product is left*right.
            // Multiplying this by the selectivity of the join condition we get
            float numTuples = leftCost.numTuples * rightCost.numTuples * selectivity;

            // Since tuple schemas are concatenated, we add the tuple sizes.
            float tupleSize = leftCost.tupleSize + rightCost.tupleSize;

            // In a nested loops join, the right table must be fully read once
            // for
            // each row in the left table. Thus, we have the left cost, plus the
            // right cost times the number of tuples on the left.

            float cpuCost = leftCost.cpuCost + leftCost.numTuples * rightCost.cpuCost;
            long numBlockIOs = leftCost.numBlockIOs + (long) Math.ceil(leftCost.numTuples) * rightCost.numBlockIOs;

            cost = new PlanCost(numTuples, tupleSize, cpuCost, numBlockIOs);
        }
    }

    @Override
    public void initialize() {
        super.initialize();

        done = false;
        leftTuple = null;
        rightTuple = null;
    }

    @Override
    public void cleanUp() {
        leftChild.cleanUp();
        rightChild.cleanUp();
    }

    /**
     * Returns the next joined tuple that satisfies the join condition.
     *
     * @return the next joined tuple that satisfies the join condition.
     *
     * @throws IOException if a db file failed to open at some point
     */
    @Override
    public Tuple getNextTuple() throws IOException {
        if (done)
            return null;

        // 嵌套循环左右tuple，在满足条件的情况下，将tuple组合
        while (getTuplesToJoin()) {
            if (canJoinTuples())
                return joinTuples(leftTuple, rightTuple);
        }

        return null;
    }

    /**
     * A JOIN B ,嵌套循环B中的记录去匹配A的记录，
     * 
     * @return 是否还有tuple没有循环到
     * @throws IOException e
     */
    private boolean getTuplesToJoin() throws IOException {
        if (leftTuple == null && !done) {
            leftTuple = leftChild.getNextTuple();
            if (leftTuple == null) {
                done = true;
                return !done;
            }
        }

        rightTuple = rightChild.getNextTuple();
        if (rightTuple == null) {
            // Reached end of right relation. Need to do two things:
            // * Go to next tuple in left relation.
            // * Restart iteration over right relation.

            leftTuple = leftChild.getNextTuple();
            if (leftTuple == null) {
                // Reached end of left relation. All done.
                done = true;
                return !done;
            }

            rightChild.initialize();
            rightTuple = rightChild.getNextTuple();
            if (rightTuple == null) {
                // Right relation is empty! All done.
                done = true;
                // Redundant: return !done;
            }
        }

        return !done;
    }

    /**
     * 判断leftTuple和rightTuple是否符合谓词，如是否符合 FROM A JOIN B ON A.ID = B.ID
     * 
     * @return leftTuple和rightTuple是否能外联
     */
    private boolean canJoinTuples() {
        // If the predicate was not set, we can always join them!
        if (predicate == null)
            return true;

        environment.clear();
        environment.addTuple(leftSchema, leftTuple);
        environment.addTuple(rightSchema, rightTuple);

        return predicate.evaluatePredicate(environment);
    }


    /**
     * Checks if the argument is a plan node tree with the same structure, but
     * not necesarily the same references.
     *
     * @param obj the object to which we are comparing
     */
    @Override
    public boolean equals(Object obj) {

        if (obj instanceof NestedLoopsJoinNode) {
            NestedLoopsJoinNode other = (NestedLoopsJoinNode) obj;

            return predicate.equals(other.predicate) && leftChild.equals(other.leftChild)
                    && rightChild.equals(other.rightChild);
        }

        return false;
    }

    /** Computes the hash-code of the nested-loops plan node. */
    @Override
    public int hashCode() {
        int hash = 7;
        hash = 31 * hash + (predicate != null ? predicate.hashCode() : 0);
        hash = 31 * hash + leftChild.hashCode();
        hash = 31 * hash + rightChild.hashCode();
        return hash;
    }

    /**
     * Returns a string representing this nested-loop join's vital information.
     *
     * @return a string representing this plan-node.
     */
    public String toString() {
        StringBuilder buf = new StringBuilder();

        buf.append("NestedLoops[");

        if (predicate != null)
            buf.append("pred:  ").append(predicate);
        else
            buf.append("no pred");

        if (schemaSwapped)
            buf.append(" (schema swapped)");

        buf.append(']');

        return buf.toString();
    }

    /**
     * Creates a copy of this plan node and its subtrees.
     */
    @Override
    protected PlanNode clone() throws CloneNotSupportedException {
        NestedLoopsJoinNode node = (NestedLoopsJoinNode) super.clone();

        // Clone the predicate.
        if (predicate != null)
            node.predicate = predicate.duplicate();
        else
            node.predicate = null;

        return node;
    }
}
