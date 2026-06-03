CREATE TABLE IF NOT EXISTS events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        VARCHAR(255) NOT NULL,
    account_id      VARCHAR(255) NOT NULL,
    type            VARCHAR(10)  NOT NULL,
    amount          DECIMAL(19,4) NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    trace_id        VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED',
    CONSTRAINT uq_gateway_event_id UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_events_account_id ON events(account_id);
