package dev.hellowrc.circlechat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** H2 compatibility smoke test; the PostgreSQL test remains the production validation. */
class ConversationMigrationTests {
    @Test
    void seedsMainConversationAndRemovesLegacyMembershipConstraintIdempotently() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:conversation-migration;MODE=PostgreSQL", "sa", "");
             var statement = connection.createStatement()) {
            // The old application has no chatroom_members table yet.
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/sql/002-conversations.sql"));
            statement.execute("""
                    create table chatroom_members (id bigint primary key, user_id bigint not null,
                    chatroom_id bigint not null, constraint unique_user_id unique (user_id))
                    """);
            statement.execute("insert into chatroom_members values (1, 1, 0)");
            for (int attempt = 0; attempt < 2; attempt++) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/sql/002-conversations.sql"));
            }
            assertThat(statement.executeUpdate("insert into chatroom_members values (2, 1, 1)")).isEqualTo(1);
            try (var rows = statement.executeQuery("select count(*) from conversations where id = 0")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            // Explicitly reserving 0 must leave the identity sequence available for regular conversations.
            assertThat(statement.executeUpdate("insert into conversations (created_at) values (CURRENT_TIMESTAMP)")).isEqualTo(1);
            try (var rows = statement.executeQuery("select id from conversations where id <> 0")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isPositive();
            }
        }
    }
}
