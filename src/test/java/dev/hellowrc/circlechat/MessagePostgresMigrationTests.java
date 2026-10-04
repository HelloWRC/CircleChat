package dev.hellowrc.circlechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Opt-in PostgreSQL verification in a newly created, isolated schema. */
@EnabledIfEnvironmentVariable(named = "CIRCLECHAT_TEST_POSTGRES_URL", matches = ".+")
class MessagePostgresMigrationTests {
    @Test
    void upgradesAndReappliesInAnIsolatedPostgresSchema() throws Exception {
        var schema = "circlechat_migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("CIRCLECHAT_TEST_POSTGRES_URL"),
                System.getenv("CIRCLECHAT_TEST_POSTGRES_USERNAME"), System.getenv("CIRCLECHAT_TEST_POSTGRES_PASSWORD"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute("CREATE TABLE messages (id bigint primary key, body varchar(255) not null, sender_id bigint not null, created_at timestamp, updated_at timestamp)");
                statement.execute("INSERT INTO messages VALUES (1, 'legacy', 1, TIMESTAMP '2026-01-01 12:00:00.123456', null), (2, 'missing audit time', 1, null, null)");
                String originalKey = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/sql/001-message-persistence.sql"));
                    try (var rows = statement.executeQuery("SELECT * FROM messages ORDER BY id")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong("conversation_id")).isZero();
                        var key = rows.getString("message_key");
                        assertThat(UUID.fromString(key).toString()).isEqualTo(key);
                        if (originalKey == null) originalKey = key;
                        else assertThat(key).isEqualTo(originalKey);
                        assertThat(rows.getTimestamp("sent_at")).isEqualTo(rows.getTimestamp("created_at"));
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getTimestamp("sent_at")).isNotNull();
                    }
                }
                try (var insert = connection.prepareStatement("INSERT INTO messages (id, body, sender_id, conversation_id, message_key, sent_at) VALUES (3, ?, 1, 0, '00000000-0000-0000-0000-000000000003', CURRENT_TIMESTAMP)")) {
                    insert.setString(1, "长消息".repeat(1000));
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
                assertThatThrownBy(() -> statement.execute("INSERT INTO messages (id, body, sender_id) VALUES (4, 'invalid', 1)"))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("INSERT INTO messages (id, body, sender_id, conversation_id, message_key, sent_at) SELECT 5, body, sender_id, conversation_id, message_key, sent_at FROM messages WHERE id=1"))
                        .isInstanceOf(java.sql.SQLException.class);
            } finally {
                // Roll back any aborted script transaction before dropping only this test's schema.
                statement.execute("ROLLBACK");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
