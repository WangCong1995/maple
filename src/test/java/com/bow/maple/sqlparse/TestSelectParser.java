package com.bow.maple.sqlparse;


import java.util.List;

import com.bow.maple.commands.FromClause;

import com.bow.maple.commands.SelectClause;
import com.bow.maple.commands.SelectCommand;
import com.bow.maple.commands.SelectValue;


/**
 * This class exercises the SQL parser on <tt>SELECT</tt> statements.
 */
public class TestSelectParser extends SqlParseTestCase {

    public void testParseSimpleSelect() throws Exception {
        NanoSqlParser parser;
        SelectClause selClause;

        parser = getParserForString("SELECT 3, a, b < 5;");
        SelectCommand cmd = (SelectCommand) parser.select_stmt();
        selClause = cmd.getSelectClause();

        assert !selClause.isDistinct();
        assert !selClause.isTrivialProject();
        assert selClause.getFromClause() == null;
        assert selClause.getWhereExpr() == null;
        assert selClause.getGroupByExprs().isEmpty();
        assert selClause.getHavingExpr() == null;
        assert selClause.getOrderByExprs().isEmpty();

        List<SelectValue> values = selClause.getSelectValues();
        assert values.size() == 3;

        // TODO:  More checking...
    }


    public void testParseSimpleSelectDistinct() throws Exception {
        NanoSqlParser parser;
        SelectClause selClause;

        parser = getParserForString("SELECT DISTINCT 3, a, b < 5;");
        SelectCommand cmd = (SelectCommand) parser.select_stmt();
        selClause = cmd.getSelectClause();

        assert  selClause.isDistinct();
        assert !selClause.isTrivialProject();
        assert selClause.getFromClause() == null;
        assert selClause.getWhereExpr() == null;
        assert selClause.getGroupByExprs().isEmpty();
        assert selClause.getHavingExpr() == null;
        assert selClause.getOrderByExprs().isEmpty();

        List<SelectValue> values = selClause.getSelectValues();
        assert values.size() == 3;

        // TODO:  More checking...
    }


    public void testParseSelectStarFrom() throws Exception {
        NanoSqlParser parser;
        SelectClause selClause;

        parser = getParserForString("SELECT * FROM foo;");
        SelectCommand cmd = (SelectCommand) parser.select_stmt();
        selClause = cmd.getSelectClause();

        assert !selClause.isDistinct();
        assert  selClause.isTrivialProject();
        assert selClause.getWhereExpr() == null;
        assert selClause.getGroupByExprs().isEmpty();
        assert selClause.getHavingExpr() == null;
        assert selClause.getOrderByExprs().isEmpty();

        List<SelectValue> values = selClause.getSelectValues();
        assert values.size() == 1;
        assert values.get(0).isWildcard();

        FromClause fromClause = selClause.getFromClause();
        assert fromClause.isBaseTable();
        assert fromClause.getTableName().equals("FOO");

        // TODO:  More checking...
    }


    public void testParseSelectFrom() throws Exception {
        NanoSqlParser parser;
        SelectClause selClause;

        parser = getParserForString("SELECT a, b + c, d FROM foo;");
        SelectCommand cmd = (SelectCommand) parser.select_stmt();
        selClause = cmd.getSelectClause();

        assert !selClause.isDistinct();
        assert !selClause.isTrivialProject();
        assert selClause.getWhereExpr() == null;
        assert selClause.getGroupByExprs().isEmpty();
        assert selClause.getHavingExpr() == null;
        assert selClause.getOrderByExprs().isEmpty();

        List<SelectValue> values = selClause.getSelectValues();
        assert values.size() == 3;

        FromClause fromClause = selClause.getFromClause();
        assert fromClause.isBaseTable();
        assert fromClause.getTableName().equals("FOO");

        // TODO:  More checking...
    }


    public void testParseSelectDistinctStarFrom() throws Exception {
        NanoSqlParser parser;
        SelectClause selClause;

        parser = getParserForString("SELECT DISTINCT * FROM bar;");
        SelectCommand cmd = (SelectCommand) parser.select_stmt();
        selClause = cmd.getSelectClause();

        assert  selClause.isDistinct();
        assert  selClause.isTrivialProject();
        assert selClause.getWhereExpr() == null;
        assert selClause.getGroupByExprs().isEmpty();
        assert selClause.getHavingExpr() == null;
        assert selClause.getOrderByExprs().isEmpty();

        List<SelectValue> values = selClause.getSelectValues();
        assert values.size() == 1;
        assert values.get(0).isWildcard();

        FromClause fromClause = selClause.getFromClause();
        assert fromClause.isBaseTable();
        assert fromClause.getTableName().equals("BAR");

        // TODO:  More checking...
    }

}
