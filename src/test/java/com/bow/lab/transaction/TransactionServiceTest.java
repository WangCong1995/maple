package com.bow.lab.transaction;

import org.junit.Before;
import org.junit.Test;

/**
 * @author vv
 * @since 2017/11/15.
 */
public class TransactionServiceTest extends AbstractTest{


    private ITransactionService service;

    @Before
    public void setup() {
        super.setup();
        service = new TransactionService(storageService);
    }

    @Test
    public void recordPageUpdate() throws Exception {
        service.initialize();
        service.startTransaction(true);
        service.recordPageUpdate(writeDbPage());
        service.commitTransaction();
    }


    @Test
    public void rollbackTransaction() throws Exception {
        service.rollbackTransaction();
    }

}