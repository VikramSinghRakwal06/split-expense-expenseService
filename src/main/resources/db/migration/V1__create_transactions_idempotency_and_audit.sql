-- PayFlow payment-service: initial schema.
--
-- This service holds NO balances. Money lives in wallet-service; what lives here is the
-- record of an attempt to move it — the saga's durable state. Three tables:
--
--   transactions        one row per transfer attempt, carrying the state machine
--   idempotency_records one row per client-supplied Idempotency-Key, with its reply
--   audit_logs          append-only trail of what happened to each transaction

CREATE TABLE transactions (
    id                 UUID          NOT NULL,

    -- The client's Idempotency-Key, verbatim. NOT NULL because a transfer may only be
    -- initiated through the idempotent path — there is deliberately no way to record a
    -- money movement here that is not keyed and therefore not safely retryable.
    --
    -- The UNIQUE constraint below is the whole point of this column. See the constraint.
    idempotency_key    VARCHAR(255)  NOT NULL,

    -- Wallet ids as minted by wallet-service. Deliberately NOT foreign keys: wallets live
    -- in a different service backed by a different database, so there is no table here to
    -- reference. This service cannot even prove a wallet exists without an HTTP call.
    sender_wallet_id   UUID          NOT NULL,
    receiver_wallet_id UUID          NOT NULL,

    -- NUMERIC(19,4), never a float type. Binary floating point cannot represent 0.10
    -- exactly, so repeated arithmetic on it drifts; money must be exact decimal. Matches
    -- wallet-service's columns digit for digit, so an amount that round-trips between the
    -- two services cannot be silently rescaled at either end.
    amount             NUMERIC(19,4) NOT NULL,

    currency           VARCHAR(3)    NOT NULL DEFAULT 'INR',

    status             VARCHAR(20)   NOT NULL,

    -- Why a transfer ended in FAILED or REVERSED, in words safe to show the payer.
    -- Null for every other state.
    failure_reason     VARCHAR(500),

    description        VARCHAR(255),

    -- Optimistic-locking counter, stamped into the WHERE clause of every UPDATE by
    -- Hibernate. A transaction row is advanced through its states by one request, but a
    -- crashed-and-recovered attempt could otherwise race a reversal; this makes the loser
    -- fail loudly instead of overwriting a state it never observed.
    version            BIGINT        NOT NULL DEFAULT 0,

    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_transactions PRIMARY KEY (id),

    -- THE ARBITER OF IDEMPOTENCY.
    --
    -- Two concurrent requests carrying the same Idempotency-Key both attempt this INSERT;
    -- exactly one commits and the other is rejected by PostgreSQL with a unique-violation.
    -- That is a decision made by the database under its own concurrency control, and it is
    -- correct no matter how the two requests interleave, which process serves them, or how
    -- many instances of this service are running.
    --
    -- A SELECT-then-INSERT in application code cannot achieve this: between the SELECT
    -- finding nothing and the INSERT running, the other request inserts, and both callers
    -- proceed to move money. The window is small and the bug is therefore rare, expensive
    -- and hard to reproduce — a duplicated payment. The service must never check first.
    CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key),

    -- A transfer of zero or a negative amount is not a transfer. The service validates
    -- this before it ever reaches the database; the constraint is what holds when a bug,
    -- a manual UPDATE, or a future code path forgets to.
    CONSTRAINT ck_transactions_amount_positive CHECK (amount > 0),

    -- Money must not move from a wallet to itself: it would produce a debit and a credit
    -- that cancel while still consuming a saga and an audit trail.
    CONSTRAINT ck_transactions_distinct_wallets CHECK (sender_wallet_id <> receiver_wallet_id),

    -- Keeps the column honest against the Java enum. Adding a state to TransactionStatus
    -- without a migration fails here at write time rather than storing a value nothing
    -- can read back.
    CONSTRAINT ck_transactions_status CHECK (
        status IN ('INITIATED', 'DEBITED', 'COMPLETED', 'FAILED', 'REVERSED')
    )
);

-- Serves GET /api/v1/payments/me for the payer: "transfers I sent, newest first".
-- The DESC matches the query's sort order, so PostgreSQL walks the index instead of
-- sorting the result set.
CREATE INDEX idx_transactions_sender_created
    ON transactions (sender_wallet_id, created_at DESC);

-- The same access path for the payee. Two separate indexes rather than one composite,
-- because a transfer is read from exactly one side at a time and no query filters on both.
CREATE INDEX idx_transactions_receiver_created
    ON transactions (receiver_wallet_id, created_at DESC);

-- The replay cache. When a client retries a request with a key it has already used, the
-- stored response is returned verbatim rather than the transfer being attempted again.
CREATE TABLE idempotency_records (
    key           VARCHAR(255) NOT NULL,

    -- Fingerprint of the request this key was first used with. A client that reuses a key
    -- with a DIFFERENT body has a bug, and returning the earlier response would answer a
    -- question they did not ask; the mismatch is rejected with 422 instead. Hashed rather
    -- than stored whole so the table does not accumulate payment payloads.
    request_hash  VARCHAR(64)  NOT NULL,

    -- The serialised reply, replayed byte for byte on a duplicate. Nullable and TEXT: it
    -- is written once the transfer reaches a terminal state, so a row inserted at the
    -- start of a flow that then crashed carries no response yet.
    response_body TEXT,
    http_status   INTEGER,

    created_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (key)
);

-- Append-only record of every state transition and every remote call outcome. Written by
-- AuditService in its own transaction (REQUIRES_NEW), so the trail of a failed payment
-- survives the rollback of the payment itself — the failures are precisely the ones worth
-- being able to reconstruct afterwards.
CREATE TABLE audit_logs (
    id             UUID         NOT NULL,

    -- Nullable, and NOT a foreign key even though the target table is in this same
    -- database: an audit row must be writable for an event that happens before the
    -- transaction row is inserted, or after it could not be. The trail's job is to record
    -- what was attempted, including attempts that never became a transaction.
    transaction_id UUID,

    event          VARCHAR(50)  NOT NULL,
    details        TEXT,
    created_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_audit_logs PRIMARY KEY (id)
);

-- Reconstructing one payment's history is the only way this table is read.
CREATE INDEX idx_audit_logs_transaction_created
    ON audit_logs (transaction_id, created_at DESC);
