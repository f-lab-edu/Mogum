package com.mogum.infra;

import com.mogum.core.TransactionRunner;
import com.mogum.support.PostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SpringTransactionRunnerIT extends PostgresTestConfiguration {
    @Autowired
    private TransactionRunner transactionRunner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void 테스트_테이블을_만든다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(
                        "CREATE TABLE transaction_runner_test (id integer PRIMARY KEY)")
                        .executeUpdate());
    }

    @AfterEach
    void 테스트_테이블을_지운다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("DROP TABLE transaction_runner_test").executeUpdate());
    }

    @Test
    void Supplier의_반환값을_돌려주고_다른_트랜잭션에서_커밋이_보인다() {
        var expected = new Object();

        var actual = transactionRunner.inTransaction(() -> {
            insertRow();
            return expected;
        });

        assertSame(expected, actual);
        assertNoTransactionResources();
        assertEquals(1L, countRowsInAnotherTransaction());
    }

    @Test
    void Runnable이_정상_종료하면_다른_트랜잭션에서_커밋이_보인다() {
        transactionRunner.inTransaction(() -> {
            insertRow();
        });

        assertNoTransactionResources();
        assertEquals(1L, countRowsInAnotherTransaction());
    }

    @Test
    void Supplier의_런타임_예외는_롤백하고_같은_객체로_전파한다() {
        var expected = new IllegalStateException("supplier failure");
        Supplier<Object> work = () -> {
            insertRow();
            throw expected;
        };

        var actual = assertThrows(IllegalStateException.class,
                () -> transactionRunner.inTransaction(work));

        assertSame(expected, actual);
        assertNoTransactionResources();
        assertEquals(0L, countRowsInAnotherTransaction());
    }

    @Test
    void Runnable의_런타임_예외는_롤백하고_같은_객체로_전파한다() {
        var expected = new IllegalStateException("runnable failure");
        Runnable work = () -> {
            insertRow();
            throw expected;
        };

        var actual = assertThrows(IllegalStateException.class,
                () -> transactionRunner.inTransaction(work));

        assertSame(expected, actual);
        assertNoTransactionResources();
        assertEquals(0L, countRowsInAnotherTransaction());
    }

    @Test
    void 두_오버로드는_블록_안에서만_트랜잭션과_DB_리소스를_유지한다() {
        assertNoTransactionResources();

        assertEquals(0L, transactionRunner.inTransaction(() -> {
            assertTransactionResources();
            return countRows();
        }));
        assertNoTransactionResources();

        transactionRunner.inTransaction(() -> {
            assertTransactionResources();
            assertEquals(0L, countRows());
        });
        assertNoTransactionResources();
    }

    private void insertRow() {
        entityManager.createNativeQuery("INSERT INTO transaction_runner_test (id) VALUES (1)")
                .executeUpdate();
    }

    private long countRowsInAnotherTransaction() {
        return new TransactionTemplate(transactionManager).execute(status -> countRows());
    }

    private long countRows() {
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM transaction_runner_test")
                .getSingleResult()).longValue();
    }

    private void assertTransactionResources() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
        assertTrue(TransactionSynchronizationManager.hasResource(entityManagerFactory));
    }

    private void assertNoTransactionResources() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
        assertFalse(TransactionSynchronizationManager.hasResource(entityManagerFactory));
    }
}
