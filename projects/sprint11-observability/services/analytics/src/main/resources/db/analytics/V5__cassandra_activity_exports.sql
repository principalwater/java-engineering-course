CREATE SEQUENCE analytics_activity_revision;
ALTER TABLE analytics_inbox ADD COLUMN activity_revision bigint;
CREATE TABLE analytics_activity_exports (
    owner_id uuid NOT NULL,
    month varchar(7) NOT NULL,
    revision bigint NOT NULL,
    exported_revision bigint NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0,
    lease_token uuid,
    lease_until timestamptz,
    PRIMARY KEY (owner_id, month)
);
CREATE INDEX analytics_activity_exports_due ON analytics_activity_exports(next_attempt_at, owner_id, month)
    WHERE exported_revision < revision;
