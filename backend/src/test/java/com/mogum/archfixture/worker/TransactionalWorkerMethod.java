package com.mogum.archfixture.worker;

import org.springframework.transaction.annotation.Transactional;

public class TransactionalWorkerMethod {
    @Transactional
    public void run() {
    }
}
