package com.bow.maple.storage;


import java.io.File;
import java.io.IOException;

import org.junit.AfterClass;
import org.junit.Test;

import com.bow.maple.client.SessionState;
import com.bow.maple.commands.FromClause;
import com.bow.maple.commands.SelectClause;
import com.bow.maple.commands.SelectValue;
import com.bow.maple.expressions.ColumnName;
import com.bow.maple.expressions.TupleLiteral;
import com.bow.maple.plans.PlanNode;
import com.bow.maple.qeval.EvalStats;
import com.bow.maple.qeval.Planner;
import com.bow.maple.qeval.PlannerFactory;
import com.bow.maple.qeval.QueryEvaluator;
import com.bow.maple.qeval.TuplePrinter;
import com.bow.maple.qeval.TupleProcessor;
import com.bow.maple.relations.ColumnInfo;
import com.bow.maple.relations.ColumnType;
import com.bow.maple.relations.SQLDataType;
import com.bow.maple.relations.TableSchema;
import com.bow.maple.relations.Tuple;
import com.bow.maple.storage.colstore.ColStoreTableManager;
import com.bow.maple.transactions.TransactionState;

/**
 * @author vv
 * @since 2017/9/16.
 */
public class StorageManagerTest {

    @Test
    public void testColumnStore() throws IOException {
        StorageManager.init();
        StorageManager storageManager = StorageManager.getInstance();
        DBFile userFile = storageManager.openDBFile("User");
        DBPage userPage0 = storageManager.loadDBPage(userFile, 0, true);
        ColStoreTableManager tableManager = new ColStoreTableManager(storageManager);
        TableFileInfo tblFileInfo = new TableFileInfo("USER", userFile);
        tableManager.addTuple(tblFileInfo , null);
    }

    /**
     * 注意配置文件config.conf中basedir改为了测试的目录
     * @throws IOException
     */
    @Test
    public void testHeapStore() throws Exception {
        StorageManager.init();
        StorageManager storageManager = StorageManager.getInstance();

        // 开启事务
        System.out.println(">>> transaction");
        TransactionState txnState = SessionState.get().getTxnState();
        txnState.setTransactionID(1);

        // 构造表结构
        System.out.println(">>> create");
        String tableName = "Employee";
        TableFileInfo tblFileInfo = new TableFileInfo(tableName);
        tblFileInfo.setFileType(DBFileType.HEAP_DATA_FILE);
        TableSchema schema = tblFileInfo.getSchema();
        ColumnType intType = new ColumnType(SQLDataType.INTEGER);
        ColumnInfo c1 = new ColumnInfo("id",tableName,intType);
        ColumnInfo c2 = new ColumnInfo("age",tableName,intType);
        schema.addColumnInfo(c1);
        schema.addColumnInfo(c2);

        // 在硬盘上创建表结构文件 模拟storageManager.createTable(tblFileInfo);
        storageManager.createTable(tblFileInfo);


        // 插入数据
        System.out.println(">>> insert");
        TupleLiteral tuple = new TupleLiteral();
        tuple.addValue(1);
        tuple.addValue(27);
        TableManager tableMgr = tblFileInfo.getTableManager();
        Tuple newTuple = tableMgr.addTuple(tblFileInfo, tuple);

        //查询数据
        System.out.println(">>> query");
        SelectClause selClause = new SelectClause();
        ColumnName wild = new ColumnName(tableName, null);
        SelectValue select= new SelectValue(wild);
        selClause.getSelectValues().add(select);

        FromClause fromClause = new FromClause(tableName, "t");
        selClause.setFromClause(fromClause);
        Planner planner = PlannerFactory.getPlanner();
        PlanNode plan = planner.makePlan(selClause);
        TupleProcessor processor = new TuplePrinter(SessionState.get().getOutputStream());
        EvalStats stats = QueryEvaluator.executePlan(plan, processor);



    }

    @AfterClass
    public static void destroy(){
        File dir = new File("./test_datafiles");
        if(dir.exists()){
            for(File f:  dir.listFiles()){
                f.delete();
            }
        }
    }

}