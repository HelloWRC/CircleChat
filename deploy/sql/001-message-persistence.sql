-- Stop the old application, back up the DB, and execute before starting the new JAR.
-- PostgreSQL; rerunning is safe. No UUID extension required.
BEGIN;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS message_key varchar(36);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS conversation_id bigint;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS sent_at timestamp(6);
ALTER TABLE messages ALTER COLUMN body TYPE text;
UPDATE messages SET conversation_id = 0 WHERE conversation_id IS NULL;
UPDATE messages SET message_key = md5('circlechat-message:' || id::text)::uuid::text WHERE message_key IS NULL;
UPDATE messages SET sent_at = COALESCE(created_at, CURRENT_TIMESTAMP::timestamp) WHERE sent_at IS NULL;
ALTER TABLE messages ALTER COLUMN conversation_id SET NOT NULL;
ALTER TABLE messages ALTER COLUMN message_key SET NOT NULL;
ALTER TABLE messages ALTER COLUMN sent_at SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_messages_message_key ON messages (message_key);
CREATE INDEX IF NOT EXISTS idx_messages_conversation_time_key ON messages (conversation_id, sent_at, message_key);
COMMIT;
