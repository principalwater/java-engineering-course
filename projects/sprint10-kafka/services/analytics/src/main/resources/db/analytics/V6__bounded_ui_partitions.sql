CREATE TABLE analytics_ui_months (
    owner_id uuid NOT NULL,
    month varchar(7) NOT NULL,
    accepted integer NOT NULL CHECK (accepted BETWEEN 1 AND 1000),
    PRIMARY KEY (owner_id, month)
);

INSERT INTO analytics_ui_months(owner_id, month, accepted)
SELECT owner_id, to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM'), count(*)
FROM analytics_inbox WHERE event_type IN ('SCREEN_OPENED', 'FORM_OPENED', 'CABINET_VIEW')
GROUP BY owner_id, to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM');
