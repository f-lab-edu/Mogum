package com.mogum.support;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 사용자 @BeforeEach보다 먼저 실제 커밋된 데이터를 지우고 공용 시계를 되돌린다. */
public final class DatabaseCleaner implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        var context = SpringExtension.getApplicationContext(extensionContext);
        clean(context.getBean(DataSource.class));
        context.getBean(TestClock.class).reset();
    }

    public void clean(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var tables = tables(connection);
                if (!tables.isEmpty()) {
                    try (var statement = connection.createStatement()) {
                        // 모든 대상을 한 문장으로 비워 NO ACTION FK와 순환 참조도 처리한다.
                        statement.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to clean test database", exception);
        }
    }

    private List<String> tables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        // 테이블을 매번 카탈로그에서 읽고 식별자 quoting도 DB에 맡긴다.
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT quote_ident(schemaname) || '.' || quote_ident(tablename)
                     FROM pg_catalog.pg_tables
                     WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history'
                     """)) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }
}
