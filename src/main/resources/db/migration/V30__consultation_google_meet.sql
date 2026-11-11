-- Meeting work is persisted on the booking so approval commits before provider calls.
ALTER TABLE consultation_bookings
    ADD COLUMN meeting_status varchar(20) NOT NULL DEFAULT 'NOT_REQUESTED'
        CHECK (meeting_status IN ('NOT_REQUESTED','PENDING','READY','FAILED','CANCELLED','ENDED')),
    ADD COLUMN meeting_space_name varchar(255),
    ADD COLUMN meeting_url text,
    ADD COLUMN calendar_event_id varchar(128),
    ADD COLUMN calendar_event_url text,
    ADD COLUMN meeting_error varchar(500),
    ADD COLUMN meeting_attempts integer NOT NULL DEFAULT 0 CHECK (meeting_attempts >= 0),
    ADD COLUMN meeting_next_attempt_at timestamptz,
    ADD COLUMN meeting_lease_token uuid,
    ADD COLUMN meeting_lease_until timestamptz,
    ADD COLUMN meeting_opened_at timestamptz,
    ADD COLUMN meeting_ended_at timestamptz,
    ADD CONSTRAINT ck_consultation_meeting_ready CHECK (
        meeting_status <> 'READY' OR
        (meeting_space_name IS NOT NULL AND meeting_url IS NOT NULL AND calendar_event_id IS NOT NULL)
    );

CREATE UNIQUE INDEX ux_consultation_meeting_space ON consultation_bookings(meeting_space_name)
    WHERE meeting_space_name IS NOT NULL;
CREATE UNIQUE INDEX ux_consultation_calendar_event ON consultation_bookings(calendar_event_id)
    WHERE calendar_event_id IS NOT NULL;
CREATE INDEX ix_consultation_meeting_work ON consultation_bookings(meeting_next_attempt_at,ends_at)
    WHERE meeting_status <> 'NOT_REQUESTED' AND meeting_ended_at IS NULL;

-- Existing future approved appointments follow the new delivery method too.
UPDATE consultation_bookings SET meeting_status='PENDING',meeting_next_attempt_at=now()
WHERE status='ACCEPTED' AND ends_at>now();
