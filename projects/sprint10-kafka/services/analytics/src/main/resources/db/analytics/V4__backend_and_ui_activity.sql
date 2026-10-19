ALTER TABLE analytics_inbox DROP CONSTRAINT analytics_inbox_event_type_check;
ALTER TABLE analytics_inbox ADD CONSTRAINT analytics_inbox_event_type_check CHECK (event_type IN
    ('DEPOSIT','WITHDRAW','TRANSFER_IN','TRANSFER_OUT','CABINET_VIEW','PROFILE_UPDATED',
     'NOTIFICATION_AVAILABLE','NOTIFICATION_READ','SCREEN_OPENED','FORM_OPENED'));
ALTER TABLE analytics_quarantine DROP CONSTRAINT analytics_quarantine_projection_check;
ALTER TABLE analytics_quarantine ADD CONSTRAINT analytics_quarantine_projection_check
    CHECK (projection IN ('PERSONAL','BUSINESS','INGRESS'));
