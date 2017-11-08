package com.bow.maple.sql;



import com.bow.maple.expressions.TupleLiteral;
import com.bow.maple.server.CommandResult;
import com.bow.maple.server.NanoDBServer;
import org.junit.Test;


/**
 * This class exercises the database with some simple <tt>SELECT</tt>
 * statements against a single table, to see if simple selects and
 * predicates work properly.
 */
public class TestSimpleSelects extends SqlTestCase {

    public TestSimpleSelects() {
        super("setup_testSimpleSelects");
    }


    /**
     * This test performs a simple <tt>SELECT</tt> statement with no predicate,
     * to see if the query produces the expected results.
     *
     * @throws Exception if any query parsing or execution issues occur.
     */
    @Test
    public void testSelectNoPredicate() throws Throwable {
        TupleLiteral[] expected = {
            new TupleLiteral(0, null),
            new TupleLiteral(1, 10),
            new TupleLiteral(2, 20),
            new TupleLiteral(3, 30),
            new TupleLiteral(4, null)
        };

        CommandResult result = NanoDBServer.doCommand(
            "SELECT * FROM test_simple_selects", true);
        assert checkUnorderedResults(expected, result);
    }


    /**
     * This test performs several simple <tt>SELECT</tt> statements with simple
     * predicates, to see if the queries produce the expected results.
     *
     * @throws Exception if any query parsing or execution issues occur.
     */
    public void testSelectSimplePredicates() throws Throwable {
        TupleLiteral[] expected1 = {
            new TupleLiteral(1, 10),
            new TupleLiteral(2, 20)
        };

        TupleLiteral[] expected2 = {
            new TupleLiteral(2, 20),
            new TupleLiteral(3, 30)
        };

        TupleLiteral[] expected3 = {
            new TupleLiteral(2, 20)
        };

        CommandResult result;

        result = NanoDBServer.doCommand(
            "SELECT * FROM test_simple_selects WHERE b < 25", true);
        assert checkUnorderedResults(expected1, result);

        result = NanoDBServer.doCommand(
            "SELECT * FROM test_simple_selects WHERE b > 15", true);
        assert checkUnorderedResults(expected2, result);

        result = NanoDBServer.doCommand(
            "SELECT * FROM test_simple_selects WHERE b > 15 AND b < 25", true);
        assert checkUnorderedResults(expected3, result);
    }
}
