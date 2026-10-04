package dev.hellowrc.circlechat;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;

/** Compatibility smoke test; production SQL must still be applied with PostgreSQL. */
public class MessageMigrationTests {
    public static String md5(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void upgradesLegacyRowsAndCanBeRerunWithoutChangingTheirKeys() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:migration;MODE=PostgreSQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE ALIAS MD5 FOR 'dev.hellowrc.circlechat.MessageMigrationTests.md5'");
            statement.execute("CREATE TABLE messages (id bigint primary key, body varchar(255) not null, sender_id bigint not null, created_at timestamp, updated_at timestamp)");
            statement.execute("INSERT INTO messages VALUES (1, 'legacy', 1, TIMESTAMP '2026-01-01 12:00:00.123456', null), (2, 'missing audit time', 1, null, null)");
            for (int attempt = 0; attempt < 2; attempt++) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/sql/001-message-persistence.sql"));
                try (var rows = statement.executeQuery("SELECT * FROM messages ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("conversation_id")).isZero();
                    assertThat(rows.getString("message_key")).isEqualTo(md5("circlechat-message:1")
                            .replaceFirst("(........)(....)(....)(....)(............)", "$1-$2-$3-$4-$5"));
                    assertThat(rows.getTimestamp("sent_at")).isEqualTo(rows.getTimestamp("created_at"));
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getTimestamp("sent_at")).isNotNull();
                    assertThat(rows.getString("message_key")).isNotBlank();
                }
            }
            try (var insert = connection.prepareStatement("INSERT INTO messages (id, body, sender_id, conversation_id, message_key, sent_at) VALUES (3, ?, 1, 0, '00000000-0000-0000-0000-000000000003', CURRENT_TIMESTAMP)")) {
                insert.setString(1, "long body".repeat(1000));
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            assertThatThrownBy(() -> statement.execute("INSERT INTO messages (id, body, sender_id) VALUES (4, 'invalid', 1)"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.execute("INSERT INTO messages (id, body, sender_id, conversation_id, message_key, sent_at) SELECT 5, body, sender_id, conversation_id, message_key, sent_at FROM messages WHERE id=1"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
