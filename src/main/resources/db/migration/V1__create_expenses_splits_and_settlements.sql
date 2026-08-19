-- SplitExpense expense-service: initial schema.
--
-- This service holds no balances. Money owed lives in group-service, as a pairwise debt
-- graph; what lives here is the record of an expense and the attempt to apply it — the
-- saga's durable state. Five tables:
--
--   expenses             one row per recorded expense, carrying the state machine
--   expense_splits       how one expense's amount is divided between its participants
--   settlements          one row per real-world payment recorded to cancel a debt
--   idempotency_records  one row per client-supplied Idempotency-Key, with its reply
--   audit_logs           append-only trail of what happened to each expense or settlement

CREATE TABLE expenses (
    id                 UUID          NOT NULL,

    -- No foreign key: groups live in group-service, behind a different database. This
    -- service cannot even prove a group exists without an HTTP call.
    group_id           UUID          NOT NULL,

    -- Who paid, as minted by auth-service. Not necessarily the caller: one member may
    -- record an expense on behalf of another who covered it in person.
    payer_user_id      UUID          NOT NULL,

    -- NUMERIC(19,4), never a float type. Binary floating point cannot represent 0.10
    -- exactly, so repeated arithmetic on it drifts; money must be exact decimal. Matches
    -- group-service's columns digit for digit, so an amount that round-trips between the
    -- two services cannot be silently rescaled at either end.
    amount             NUMERIC(19,4) NOT NULL,

    currency           VARCHAR(3)    NOT NULL DEFAULT 'INR',

    description        VARCHAR(255)  NOT NULL,

    split_type         VARCHAR(20)   NOT NULL,

    status             VARCHAR(20)   NOT NULL,

    -- Why an expense ended FAILED, in words safe to show whoever recorded it. Null for
    -- every other state.
    failure_reason     VARCHAR(500),

    -- The client's Idempotency-Key, verbatim. NOT NULL because an expense may only be
    -- recorded through the idempotent path — there is deliberately no way to record one
    -- here that is not keyed and therefore not safely retryable.
    idempotency_key    VARCHAR(255)  NOT NULL,

    -- Optimistic-locking counter, stamped into the WHERE clause of every UPDATE by
    -- Hibernate. An expense row is advanced through its states by one request, but a
    -- crashed-and-recovered attempt could otherwise race a void; this makes the loser fail
    -- loudly instead of overwriting a state it never observed.
    version            BIGINT        NOT NULL DEFAULT 0,

    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_expenses PRIMARY KEY (id),

    -- THE ARBITER OF IDEMPOTENCY. Two concurrent requests carrying the same Idempotency-Key
    -- both attempt this INSERT; exactly one commits and the other is rejected by PostgreSQL
    -- with a unique-violation. That is a decision made by the database under its own
    -- concurrency control, and it is correct no matter how the two requests interleave,
    -- which process serves them, or how many instances of this service are running.
    CONSTRAINT uq_expenses_idempotency_key UNIQUE (idempotency_key),

    -- An expense of zero or a negative amount is not an expense. The service validates
    -- this before it ever reaches the database; the constraint is what holds when a bug,
    -- a manual UPDATE, or a future code path forgets to.
    CONSTRAINT ck_expenses_amount_positive CHECK (amount > 0),

    -- Keeps the column honest against the Java enum. Adding a value without a migration
    -- fails here at write time rather than storing something nothing can read back.
    CONSTRAINT ck_expenses_split_type
        CHECK (split_type IN ('EQUAL', 'EXACT', 'PERCENTAGE', 'SHARES')),

    CONSTRAINT ck_expenses_status
        CHECK (status IN ('INITIATED', 'COMPLETED', 'FAILED', 'VOIDED'))
);

-- Serves GET /api/v1/groups/{groupId}/expenses and the caller's own history: "this group's
-- expenses, newest first". The DESC matches the query's sort order, so PostgreSQL walks the
-- index instead of sorting the result set.
CREATE INDEX idx_expenses_group_created ON expenses (group_id, created_at DESC);

-- Serves GET /api/v1/expenses/me for the payer side of "expenses I'm involved in".
CREATE INDEX idx_expenses_payer_created ON expenses (payer_user_id, created_at DESC);

-- How one expense's amount is divided. The payer normally appears here too, with their own
-- share — they paid the whole bill but only consumed part of it, and the delta sent to
-- group-service excludes exactly that row: the payer cannot owe themselves.
CREATE TABLE expense_splits (
    id           UUID          NOT NULL,
    expense_id   UUID          NOT NULL,
    user_id      UUID          NOT NULL,
    share_amount NUMERIC(19,4) NOT NULL,

    CONSTRAINT pk_expense_splits PRIMARY KEY (id),

    -- Same database, same service: here a foreign key is both possible and correct. No ON
    -- DELETE clause, because an expense is voided by status, never deleted; the splits of a
    -- voided expense remain as a record of what the original charge actually was.
    CONSTRAINT fk_expense_splits_expense FOREIGN KEY (expense_id) REFERENCES expenses (id),

    -- One row per participant per expense; the arbiter that keeps a split computation from
    -- accidentally writing somebody twice.
    CONSTRAINT uq_expense_splits_member UNIQUE (expense_id, user_id),

    -- A share of exactly zero is legal (SplitCalculator's residual allocation never produces
    -- one, but an EXACT split naming a participant for 0.00 is a valid, if odd, request);
    -- a negative share is not a share.
    CONSTRAINT ck_expense_splits_non_negative CHECK (share_amount >= 0)
);

-- Serves "give me this expense's splits" — the only way the table is ever read on its own.
CREATE INDEX idx_expense_splits_expense ON expense_splits (expense_id);

-- A real-world payment between two members, recorded to cancel debt. It moves no money
-- here either — this service records that money moved elsewhere, exactly like an expense.
CREATE TABLE settlements (
    id              UUID          NOT NULL,
    group_id        UUID          NOT NULL,
    from_user_id    UUID          NOT NULL,
    to_user_id      UUID          NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    currency        VARCHAR(3)    NOT NULL DEFAULT 'INR',
    status          VARCHAR(20)   NOT NULL,
    failure_reason  VARCHAR(500),
    idempotency_key VARCHAR(255)  NOT NULL,
    note            VARCHAR(255),
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_settlements PRIMARY KEY (id),
    CONSTRAINT uq_settlements_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_settlements_amount_positive CHECK (amount > 0),

    -- Money must not settle from a person to themselves: it would produce a delta that
    -- cancels while still consuming a saga and an audit trail.
    CONSTRAINT ck_settlements_distinct_users CHECK (from_user_id <> to_user_id),

    CONSTRAINT ck_settlements_status CHECK (status IN ('INITIATED', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_settlements_group_created ON settlements (group_id, created_at DESC);

-- The replay cache, shared by expenses and settlements alike: a client retrying a request
-- with a key it has already used gets the stored response back verbatim rather than the
-- operation being attempted again. Identical in shape and purpose to payment-service's
-- table of the same name.
CREATE TABLE idempotency_records (
    key           VARCHAR(255) NOT NULL,

    -- Fingerprint of the request this key was first used with. A client that reuses a key
    -- with a DIFFERENT body has a bug, and returning the earlier response would answer a
    -- question they did not ask; the mismatch is rejected with 422 instead. Hashed rather
    -- than stored whole so the table does not accumulate request payloads.
    request_hash  VARCHAR(64)  NOT NULL,

    -- The serialised reply, replayed byte for byte on a duplicate. Nullable and TEXT: it is
    -- written once the operation reaches a terminal state, so a row inserted at the start of
    -- a flow that then crashed carries no response yet.
    response_body TEXT,
    http_status   INTEGER,

    created_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (key)
);

-- Append-only record of every state transition and every remote call outcome, for both
-- expenses and settlements. Written by AuditService in its own transaction (REQUIRES_NEW),
-- so the trail of a failed operation survives the rollback of the operation itself — the
-- failures are precisely the ones worth being able to reconstruct afterwards.
CREATE TABLE audit_logs (
    id          UUID         NOT NULL,

    -- The expense or settlement this event concerns. Nullable, and NOT a foreign key even
    -- though both target tables are in this same database: an audit row must be writable
    -- for an event that happens before that row is inserted, or after it could not be. The
    -- trail's job is to record what was attempted, including attempts that never became a
    -- row in either table.
    subject_id  UUID,

    event       VARCHAR(50)  NOT NULL,
    details     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_audit_logs PRIMARY KEY (id)
);

-- Reconstructing one expense's or settlement's history is the only way this table is read.
CREATE INDEX idx_audit_logs_subject_created ON audit_logs (subject_id, created_at DESC);
