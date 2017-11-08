package com.bow.maple.qeval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bow.maple.commands.SelectValue;
import com.bow.maple.expressions.ColumnName;
import com.bow.maple.expressions.Expression;
import com.bow.maple.plans.CSProjectNode;
import com.bow.maple.plans.FileScanNode;
import com.bow.maple.plans.NestedLoopsJoinNode;
import com.bow.maple.plans.SelectNode;
import com.bow.maple.plans.SortNode;
import com.bow.maple.relations.JoinType;
import com.bow.maple.relations.Schema;
import com.bow.maple.storage.StorageManager;
import com.bow.maple.storage.TableFileInfo;
import org.apache.log4j.Logger;

import com.bow.maple.commands.FromClause;
import com.bow.maple.commands.SelectClause;
import com.bow.maple.expressions.BooleanOperator;
import com.bow.maple.expressions.OrderByExpression;
import com.bow.maple.plans.PlanNode;
import com.bow.maple.plans.ProjectNode;
import com.bow.maple.plans.RenameNode;
import com.bow.maple.plans.SimpleFilterNode;
import com.bow.maple.storage.DBFileType;

/**
 * This planner implementation uses dynamic programming to devise an optimal
 * join strategy for the query. As always, queries are optimized in units of
 * <tt>SELECT</tt>-<tt>FROM</tt>-<tt>WHERE</tt> subqueries; optimizations don't
 * currently span multiple subqueries.
 */
public class DPJoinPlanner implements Planner {

    private static Logger logger = Logger.getLogger(DPJoinPlanner.class);

    /**
     * 一个JoinComponent就是一个连接1个或者多个叶子的query plan。<br/>
     * leaf是基表或者是FROM子句里的subquery。<br/>
     * Planner尽可能的下推谓词
     */
    private static class JoinComponent {

        /**
         * 将多个叶子{@link #leavesUsed}连接在一起的PlanNode
         */
        public PlanNode joinPlan;

        /**
         * 用于生成{@link #joinPlan}的叶子
         */
        public Set<PlanNode> leavesUsed;

        /**
         * 生成{@link #joinPlan}用到的谓词
         */
        public Set<Expression> conjunctsUsed;

        /**
         * 此构造器只是简单的将leaf node包装成一个JoinComponent<br/>
         * 它并不能将多个叶子连接成一个join-plan
         * 
         * @param leafPlan 叶子节点，基表或者是FROM子句里的subquery
         * @param conjunctsUsed 用到的谓词
         */
        public JoinComponent(PlanNode leafPlan, HashSet<Expression> conjunctsUsed) {
            leavesUsed = new HashSet<PlanNode>();
            leavesUsed.add(leafPlan);

            joinPlan = leafPlan;

            this.conjunctsUsed = conjunctsUsed;
        }

        /**
         * 构造一个<em>non-leaf node</em>的JoinComponent，通过一个Join Plan将多个叶子连接起来。
         * 
         * @param joinPlan 将leavesUsed中的叶子连接起来的Join Plan
         * @param leavesUsed 被连接的叶子
         * @param conjunctsUsed 添加在joinPlan里的谓词
         */
        public JoinComponent(PlanNode joinPlan, HashSet<PlanNode> leavesUsed, HashSet<Expression> conjunctsUsed) {
            this.joinPlan = joinPlan;
            this.leavesUsed = leavesUsed;
            this.conjunctsUsed = conjunctsUsed;
        }
    }

    /**
     * 为查询语句生成一个执行计划
     * 
     * @param selClause an object describing the query to be performed
     *
     * @return 执行计划
     * @throws IOException e
     */
    public PlanNode makePlan(SelectClause selClause) throws IOException {

        FromClause fromClause = selClause.getFromClause();
        if (fromClause == null) {
            throw new UnsupportedOperationException("NanoDB doesn't yet support SQL queries without a FROM clause!");
        }

        // If we have a columnstore table, we can create a separate plan for
        // that.
        if (fromClause.isBaseTable()) {
            TableFileInfo tableInfo = StorageManager.getInstance().openTable(fromClause.getTableName());

            if (tableInfo.getFileType() == DBFileType.CS_DATA_FILE) {
                logger.debug("Jumping to ColumnStore planner.");
                PlanNode plan = new CSProjectNode(selClause, tableInfo);
                plan.prepare();
                return plan;
            }
        }

        // 将WHERE后的谓词放入whereConjuncts
        HashSet<Expression> whereConjuncts = new HashSet<Expression>();
        addConjuncts(whereConjuncts, selClause.getWhereExpr());

        // 通过谓词下移的方式构造JOIN
        JoinComponent joinComp = makeJoinPlan(fromClause, whereConjuncts);
        PlanNode plan = joinComp.joinPlan;

        // 找出没有用到的谓词，添加到执行计划中
        HashSet<Expression> unusedConjuncts = new HashSet<Expression>(whereConjuncts);
        unusedConjuncts.removeAll(joinComp.conjunctsUsed);
        Expression finalPredicate = makePredicate(unusedConjuncts);
        if (finalPredicate != null) {
            plan = addPredicateToPlan(plan, finalPredicate);
        }

        // TODO: Grouping/aggregation will go somewhere in here.

        // Depending on the SELECT clause, create a project node at the top of
        // the tree.
        if (!selClause.isTrivialProject()) {
            List<SelectValue> selectValues = selClause.getSelectValues();
            plan = new ProjectNode(plan, selectValues);
        }

        // Finally, apply any sorting at the end.
        List<OrderByExpression> orderByExprs = selClause.getOrderByExprs();
        if (!orderByExprs.isEmpty())
            plan = new SortNode(plan, orderByExprs);

        plan.prepare();

        return plan;
    }

    /**
     * 生成一个连接计划
     * 
     * @param fromClause FROM ...
     * @param extraConjuncts 外界传入的谓词，便于在连接前就过滤
     * @return 连接计划
     * @throws IOException
     */
    private JoinComponent makeJoinPlan(FromClause fromClause, Collection<Expression> extraConjuncts)
            throws IOException {

        // 将表名收集到leafFromClauses，连接条件收集到conjuncts
        HashSet<Expression> conjuncts = new HashSet<Expression>();
        ArrayList<FromClause> leafFromClauses = new ArrayList<FromClause>();
        collectDetails(fromClause, conjuncts, leafFromClauses);

        logger.debug("Making join-plan for " + fromClause);
        logger.debug("    Collected conjuncts:  " + conjuncts);
        logger.debug("    Collected FROM-clauses:  " + leafFromClauses);
        logger.debug("    Extra conjuncts:  " + extraConjuncts);

        // WHERE条件谓词和JOIN连接谓词合并
        if (extraConjuncts != null) {
            conjuncts.addAll(extraConjuncts);
        }
        // 改为read only
        Set<Expression> roConjuncts = Collections.unmodifiableSet(conjuncts);

        logger.debug("Generating plans for all leaves");
        List<JoinComponent> leafComponents = generateLeafJoinComponents(leafFromClauses, roConjuncts);

        // Print out the results, for debugging purposes.
        if (logger.isDebugEnabled()) {
            for (JoinComponent leaf : leafComponents) {
                logger.debug("    Leaf plan:  " + PlanNode.printNodeTreeToString(leaf.joinPlan, true));
            }
        }

        // Build up the full query-plan using a dynamic programming approach.

        JoinComponent optimalJoin = generateOptimalJoin(leafComponents, roConjuncts);

        PlanNode plan = optimalJoin.joinPlan;
        logger.info("Optimal join plan generated:\n" + PlanNode.printNodeTreeToString(plan, true));

        // If there are any unused predicates that we can apply at this level,
        // then we need to add those into the plan.
        // TODO ???

        return optimalJoin;
    }

    /**
     * 将from语句中，内联的条件收集到conjuncts，表名放入leafFromClauses
     *
     * @param fromClause the from-clause to collect details from
     *
     * @param conjuncts 各表连接条件
     *
     * @param leafFromClauses 收集的基表表名
     */
    private void collectDetails(FromClause fromClause, HashSet<Expression> conjuncts,
            ArrayList<FromClause> leafFromClauses) {

        // 只特殊处理内联
        if (fromClause.getClauseType() == FromClause.ClauseType.JOIN_EXPR && !fromClause.isOuterJoin()) {
            // 将各个join的连接条件取出放入conjuncts
            FromClause.JoinConditionType condType = fromClause.getConditionType();
            if (condType != null) {
                addConjuncts(conjuncts, fromClause.getPreparedJoinExpr());
            }

            collectDetails(fromClause.getLeftChild(), conjuncts, leafFromClauses);
            collectDetails(fromClause.getRightChild(), conjuncts, leafFromClauses);
        } else {
            // 如果他是 base table, subquery或者outer join 就放到叶子列表中
            leafFromClauses.add(fromClause);
        }
    }

    /**
     * 将expr对应的谓词添加到conjuncts，如果Expression是AND表达式就将其内部的各个term放入到conjuncts中，
     * 若不是将整个expression当作谓词
     * 
     * @param conjuncts predicate容器
     * @param expr 待分析的表达式
     */
    private void addConjuncts(Collection<Expression> conjuncts, Expression expr) {

        if (expr == null) {
            return;
        }

        // 若不是BooleanOperator则将整个expr放入conjuncts
        if (!(expr instanceof BooleanOperator)) {
            conjuncts.add(expr);
            return;
        }

        BooleanOperator boolExpr = (BooleanOperator) expr;
        // 若不是AND则将整个expr放入conjuncts
        if (boolExpr.getType() != BooleanOperator.Type.AND_EXPR) {
            conjuncts.add(expr);
            return;
        }

        // 若是AND，则将各个term取出放到conjuncts
        for (int iTerm = 0; iTerm < boolExpr.getNumTerms(); iTerm++) {
            conjuncts.add(boolExpr.getTerm(iTerm));
        }
    }

    /**
     * 将leafFromClause转换成JoinComponent，生成时注意谓词下推
     * 
     * @param leafFromClauses 基表，子查询或是外联的clause
     * @param conjuncts 可能有用的谓词
     * @return leafFromClauses对应的JoinComponent
     * @throws IOException e
     */
    private List<JoinComponent> generateLeafJoinComponents(Collection<FromClause> leafFromClauses,
            Collection<Expression> conjuncts) throws IOException {

        ArrayList<JoinComponent> result = new ArrayList<JoinComponent>();
        for (FromClause leafClause : leafFromClauses) {

            // 将被下移的谓词放入leafConjuncts
            HashSet<Expression> leafConjuncts = new HashSet<Expression>();
            // 为leafClause生成leafPlan，主要考虑要让谓词下移
            PlanNode leafPlan = makeLeafPlan(leafClause, conjuncts, leafConjuncts);

            JoinComponent leaf = new JoinComponent(leafPlan, leafConjuncts);
            result.add(leaf);
        }

        return result;
    }

    /**
     * 谓词下推 Constructs a plan tree for evaluating the specified from-clause.
     * Depending on the clause's {@link FromClause#getClauseType type}, the plan
     * tree will comprise varying operations, such as:
     * <ul>
     * <li>{@link FromClause.ClauseType#BASE_TABLE}
     * - the clause is a simple table reference, so a simple select operation is
     * constructed via {@link #makeSimpleSelect}.</li>
     * <li>
     * {@link FromClause.ClauseType#SELECT_SUBQUERY}
     * - the clause is a <tt>SELECT</tt> subquery, so a plan subtree is
     * constructed by a recursive call to {@link #makePlan}.</li>
     * <li>{@link FromClause.ClauseType#JOIN_EXPR}
     * <b>(outer joins only!)</b> - the clause is an outer join of two
     * relations. Because outer joins are so constrained in what conjuncts can
     * be pushed down through them, we treat them as leaf-components in this
     * optimizer as well. The child-plans of the outer join are constructed by
     * recursively invoking the {@link #makeJoinPlan} method, and then an outer
     * join is constructed from the two children.</li>
     * </ul>
     *
     * @param fromClause the select nodes that need to be joined.
     *
     * @return a plan tree for evaluating the specified from-clause
     *
     * @throws IOException if an IO error occurs when the planner attempts to
     *         load schema and indexing information.
     *
     * @throws IllegalArgumentException if the specified from-clause is a join
     *         expression that isn't an outer join, or has some other
     *         unrecognized type.
     */
    private PlanNode makeLeafPlan(FromClause fromClause, Collection<Expression> conjuncts,
            HashSet<Expression> leafConjuncts) throws IOException {

        PlanNode plan;

        FromClause.ClauseType clauseType = fromClause.getClauseType();
        switch (clauseType) {
            case BASE_TABLE:
            case SELECT_SUBQUERY:

                if (clauseType == FromClause.ClauseType.SELECT_SUBQUERY) {
                    // 构建子查询的执行计划
                    plan = makePlan(fromClause.getSelectClause());
                } else {
                    // 基表
                    plan = makeSimpleSelect(fromClause.getTableName(), null);
                }

                // 如果有别名
                if (fromClause.isRenamed()) {
                    plan = new RenameNode(plan, fromClause.getResultName());
                }

                // 获取此执行节点的schema
                plan.prepare();
                Schema schema = plan.getSchema();

                // 将conjuncts里已在schema中定义了的谓词移动到leafConjuncts
                findExprsUsingSchemas(conjuncts, false, leafConjuncts, schema);
                // 将这些谓词合并成一个
                Expression leafPredicate = makePredicate(leafConjuncts);
                if (leafPredicate != null) {
                    // 谓词下移
                    plan = addPredicateToPlan(plan, leafPredicate);
                }
                break;

            case JOIN_EXPR:
                if (!fromClause.isOuterJoin()) {
                    throw new IllegalArgumentException("This method only supports outer joins.  Got " + fromClause);
                }

                // 如果是左外连，左表不能过滤，对右表使用谓词进行过滤
                // 如果是右外连，右表不能过滤，对左表使用谓词进行过滤
                Collection<Expression> childConjuncts = conjuncts;
                if (fromClause.hasOuterJoinOnRight()) {
                    childConjuncts = null;
                }
                JoinComponent leftComp = makeJoinPlan(fromClause.getLeftChild(), childConjuncts);

                childConjuncts = conjuncts;
                if (fromClause.hasOuterJoinOnLeft()) {
                    childConjuncts = null;
                }
                JoinComponent rightComp = makeJoinPlan(fromClause.getRightChild(), childConjuncts);

                plan = new NestedLoopsJoinNode(leftComp.joinPlan, rightComp.joinPlan, fromClause.getJoinType(),
                        fromClause.getPreparedJoinExpr());

                leafConjuncts.addAll(leftComp.conjunctsUsed);
                leafConjuncts.addAll(rightComp.conjunctsUsed);

                break;

            default:
                throw new IllegalArgumentException("Unrecognized from-clause type:  " + fromClause.getClauseType());
        }

        plan.prepare();

        return plan;
    }

    /**
     * 根据已有的叶子节点和谓词构造一个最优(cost最小)的JoinComponent<br/>
     * 动态规划可解决最短路径问题。N个节点对应一个最优解集合，在此基础上生成N+1节点的最优解集合。
     *
     * A,B,C的最优连接方式<br/>
     * 1. AB连接有A JOIN B和B JOIN A两种方式，A和B生成一个最优解A-B,同理BC生成B-C,AC生成A-C<br/>
     * 2. A-B循环连接A,B,C,发现只有A-B-C有效（在A-B连接B时，会发现B已在连接中了）<br/>
     * 同理生成B-C-A, A-C-B,此时会从其中找出cost最小的连接。
     *
     * 
     * @param leafComponents 对leaf clause的包装，{@link #generateLeafJoinComponents}
     * @param conjuncts 谓词
     * @return 最优连结方式
     */
    private JoinComponent generateOptimalJoin(List<JoinComponent> leafComponents, Set<Expression> conjuncts) {

        Map<Set<PlanNode>, JoinComponent> joinPlans = new HashMap();

        // 用叶子节点进行初始化
        for (JoinComponent leaf : leafComponents) {
            joinPlans.put(leaf.leavesUsed, leaf);
        }

        // 一致迭代直到最后形成一个最优解
        while (joinPlans.size() > 1) {
            // 每次迭代的结果放在此处
            // Map<包含的叶子元素集, JoinComponent>，叶子元素集对应的JoinComponent一定是这些叶子元素的最优连接
            Map<Set<PlanNode>, JoinComponent> nextJoinPlans = new HashMap();

            // 迭代前次的连接结果
            for (JoinComponent prevComponent : joinPlans.values()) {

                // 在已形成的最优解prevComponent上尝试添加子节点
                List<JoinComponent> results = tryAppendLeaf(prevComponent, leafComponents, conjuncts);

                for (JoinComponent component : results) {
                    // 找出相同叶子元素对应的最优连接
                    Set<PlanNode> leaves = component.leavesUsed;
                    JoinComponent currentBest = nextJoinPlans.get(leaves);
                    if (currentBest == null) {
                        logger.info("Setting current best-plan.");
                        nextJoinPlans.put(leaves, component);
                    } else {
                        PlanCost bestCost = currentBest.joinPlan.getCost();
                        // 新的连接cost更小则替换
                        if (component.joinPlan.getCost().cpuCost < bestCost.cpuCost) {
                            logger.info("Replacing current best-plan with new plan!");
                            nextJoinPlans.put(leaves, component);
                        }
                    }
                }
            }

            // Now that we have generated all plans joining N leaves, time to
            // create all plans joining N + 1 leaves.
            joinPlans = nextJoinPlans;
        }

        // 到此处应该为唯一最优解
        assert joinPlans.size() == 1 : "There can be only one optimal join plan!";
        return joinPlans.values().iterator().next();
    }

    /**
     * 尝试着在最优连接prevComponent继续添加叶子元素
     * 
     * @param prevComponent 前一次形成的最优连接
     * @param leafComponents 叶子元素
     * @param conjuncts 谓词
     * @return 多连一个leaf后的JoinComponent
     */
    private List<JoinComponent> tryAppendLeaf(JoinComponent prevComponent, List<JoinComponent> leafComponents,
            Set<Expression> conjuncts) {
        // 取出前次迭代的一个结果，某些元素的最优连接
        Set<PlanNode> prevLeavesUsed = prevComponent.leavesUsed;
        PlanNode prevPlan = prevComponent.joinPlan;
        Set<Expression> prevConjunctsUsed = prevComponent.conjunctsUsed;
        Schema prevSchema = prevPlan.getSchema();

        List<JoinComponent> results = new ArrayList<>();
        // 给最优解再连一个节点
        for (JoinComponent leaf : leafComponents) {
            PlanNode leafPlan = leaf.joinPlan;

            // 需要增加的元素，在最优解中已包含了，就跳过
            if (prevLeavesUsed.contains(leafPlan)) {
                continue;
            }

            // 加入新叶子节点
            HashSet<PlanNode> newLeavesUsed = new HashSet<PlanNode>(prevLeavesUsed);
            newLeavesUsed.add(leafPlan);
            Schema leafSchema = leafPlan.getSchema();

            // 加入此叶子节点对应的谓词
            HashSet<Expression> subplanConjuncts = new HashSet<Expression>(prevConjunctsUsed);
            subplanConjuncts.addAll(leaf.conjunctsUsed);

            // 找出没用到的谓词，用作连接谓词
            HashSet<Expression> unusedConjuncts = new HashSet<Expression>(conjuncts);
            unusedConjuncts.removeAll(subplanConjuncts);
            HashSet<Expression> joinConjuncts = new HashSet<Expression>();
            findExprsUsingSchemas(unusedConjuncts, true, joinConjuncts, leafSchema, prevSchema);

            Expression joinPredicate = makePredicate(joinConjuncts);

            // 将leafPlan连接到已有最优节点prevPlan，注意此处始终为prevPlan left join leafPlan
            NestedLoopsJoinNode newJoinPlan = new NestedLoopsJoinNode(prevPlan, leafPlan, JoinType.INNER,
                    joinPredicate);
            newJoinPlan.prepare();

            joinConjuncts.addAll(subplanConjuncts);
            JoinComponent joinComponent = new JoinComponent(newJoinPlan, newLeavesUsed, joinConjuncts);
            results.add(joinComponent);
        }
        return results;
    }

    /**
     * 将连接条件合并成一个谓词
     * 
     * @param conjuncts 各个表之间连接条件或是where后的限定条件
     * @return AND连接的谓词
     */
    private Expression makePredicate(Collection<Expression> conjuncts) {
        Expression predicate = null;
        if (conjuncts.size() == 1) {
            predicate = conjuncts.iterator().next();
        } else if (conjuncts.size() > 1) {
            predicate = new BooleanOperator(BooleanOperator.Type.AND_EXPR, conjuncts);
        }
        return predicate;
    }

    /**
     * 给PlanNode添加谓词，遵循谓词越靠近数据源效率越高
     * 
     * @param plan 计划节点
     * @param predicate 谓词，判断tuple是否满足条件
     * @return 添加谓词后的执行计划
     */
    public static PlanNode addPredicateToPlan(PlanNode plan, Expression predicate) {
        if (!(plan instanceof SelectNode)) {
            // 如果不是SelectNode直接将过滤条件包在外面
            return new SimpleFilterNode(plan, predicate);
        }
        SelectNode selectNode = (SelectNode) plan;
        // selectNode之前没有谓词，则将谓词下移
        if (selectNode.predicate == null) {
            selectNode.predicate = predicate;
            return selectNode;
        }

        // 取出已经存在的谓词
        Expression existsPred = selectNode.predicate;

        // 已有谓词若是AND则将新谓词加入到其term中
        if (existsPred instanceof BooleanOperator) {
            BooleanOperator bool = (BooleanOperator) existsPred;
            if (bool.getType() == BooleanOperator.Type.AND_EXPR) {
                bool.addTerm(predicate);
                return selectNode;
            }
        }

        // 已有谓词不是AND则创建一个AND，将fsPred和predicate加入到term中
        BooleanOperator bool = new BooleanOperator(BooleanOperator.Type.AND_EXPR);
        bool.addTerm(existsPred);
        bool.addTerm(predicate);
        selectNode.predicate = bool;
        return selectNode;
    }

    /**
     * 对于srcExprs中的Expression，若其所有columnName在schema中能找到定义，就将其移动到dstExprs中
     * 
     * @param srcExprs expression集合
     * @param remove true则移动时会删除srcExprs中的元素
     * @param dstExprs expression集合
     * @param schemas schema数组
     */
    public static void findExprsUsingSchemas(Collection<Expression> srcExprs, boolean remove,
            Collection<Expression> dstExprs, Schema... schemas) {

        List<ColumnName> columnNames = new ArrayList<ColumnName>();

        Iterator<Expression> expItr = srcExprs.iterator();
        while (expItr.hasNext()) {
            Expression expr = expItr.next();

            // 获取expression中所有columnNames
            columnNames.clear();
            expr.getAllSymbols(columnNames);

            // 若此expression所有column都能在schema中找到定义则移动到dstExprs
            boolean allRef = true;
            for (ColumnName colName : columnNames) {
                // 查看当前colName是否在schemas中定义
                boolean ref = false;
                for (Schema schema : schemas) {
                    if (schema.getColumnIndex(colName) != -1) {
                        ref = true;
                        break;
                    }
                }

                // 只要有一个colName没有在schema中找到定义,当前expression就不能移动
                if (!ref) {
                    allRef = false;
                    break;
                }
            }

            // 若所有term都能找到定义则移动到dstExprs中
            if (allRef) {
                dstExprs.add(expr);
                if (remove) {
                    expItr.remove();
                }
            }
        }
    }

    /**
     * 构建一个扫描表的执行计划
     * 
     * @param tableName the table that the select will operate against
     * @param predicate the selection predicate to apply, or <tt>null</tt> if
     *        all tuples in the table should be returned
     *
     * @return FileScanNode
     * @throws IOException 文件不存在等异常
     */
    public SelectNode makeSimpleSelect(String tableName, Expression predicate) throws IOException {

        // Open the table.
        TableFileInfo tableInfo = StorageManager.getInstance().openTable(tableName);

        // Make a SelectNode to read rows from the table, with the specified
        // predicate.
        return new FileScanNode(tableInfo, predicate);
    }
}
