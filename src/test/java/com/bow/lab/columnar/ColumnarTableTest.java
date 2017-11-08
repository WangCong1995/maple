package com.bow.lab.columnar;

import com.bow.maple.commands.CreateTableCommand;
import com.bow.maple.commands.FromClause;
import com.bow.maple.commands.LoadFileCommand;
import com.bow.maple.commands.SelectClause;
import com.bow.maple.commands.SelectCommand;
import com.bow.maple.commands.SelectValue;
import com.bow.maple.expressions.ColumnName;
import com.bow.maple.expressions.ColumnValue;
import com.bow.maple.expressions.Expression;
import com.bow.maple.relations.ColumnInfo;
import com.bow.maple.relations.ColumnType;
import com.bow.maple.relations.SQLDataType;
import com.bow.maple.storage.StorageManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

/**
 * @author vv
 * @since 2017/10/22.
 */
public class ColumnarTableTest {

    @Before
    public void setup() throws IOException {
        StorageManager.init();
    }

    @After
    public void destroy() throws IOException {
        StorageManager.shutdown();
    }

    @Test
    public void create() throws Exception {
        CreateTableCommand createTableCommand = new CreateTableCommand("states", false, false);
        createTableCommand.setEngine("columnStore");
        ColumnType strType = new ColumnType(SQLDataType.VARCHAR);
        ColumnType intType = new ColumnType(SQLDataType.INTEGER);
        strType.setLength(10);
        ColumnInfo id = new ColumnInfo("id", "states", intType);
        ColumnInfo name = new ColumnInfo("name", "states", strType);
        createTableCommand.addColumn(id);
        createTableCommand.addColumn(name);
        createTableCommand.execute();
    }

    /**
     * 将数据导入到列式数据库中
     * 
     * @throws Exception e
     */
    @Test
    public void load() throws Exception {
        LoadFileCommand command = new LoadFileCommand("states", "states.txt");
        command.execute();
    }

    @Test
    public void select() throws Exception {
        ColumnName idCn = new ColumnName("id");
        Expression idCv = new ColumnValue(idCn);
        SelectValue idVal = new SelectValue(idCv, null);
        SelectClause clause = new SelectClause();
        clause.addSelectValue(idVal);
        FromClause from = new FromClause("states", null);
        clause.setFromClause(from);
        SelectCommand command = new SelectCommand(clause);
        command.execute();
    }
}
