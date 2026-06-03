CREATE TABLE IF NOT EXISTS transactions (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       VARCHAR(255) NOT NULL,
    account_id     VARCHAR(255) NOT NULL,
    type           VARCHAR(10)  NOT NULL,
    amount         DECIMAL(19,4) NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    applied_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_event_id UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_transactions_account_id ON transactions(account_id);
