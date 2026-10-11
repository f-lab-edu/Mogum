package com.mogum.core;

import java.util.function.Supplier;

public interface TransactionRunner {
    <T> T inTransaction(Supplier<T> work);

    void inTransaction(Runnable work);
}
