BEGIN;
CREATE INDEX IF NOT EXISTS outbox_retention_idx ON eventing.outbox (published_at, event_id)
    WHERE published_at IS NOT NULL;
COMMIT;
