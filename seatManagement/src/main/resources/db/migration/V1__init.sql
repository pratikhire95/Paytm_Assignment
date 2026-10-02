-- V1: seat reservation schema.
--
-- Design notes (the "why" behind each structure; WRITEUP.md has the long version):
--
--  * seats  : one row per seat = the single source of truth for that seat. A seat is claimed by a
--             guarded statement taken under a row lock (status must still be 'available'), so two
--             buyers can never both win it. CHECK seats_owner_consistent makes an "owned but ownerless"
--             or "available but owned" row unrepresentable.
--  * reservations : carries the idempotency key. UNIQUE (user_id, idempotency_key) turns
--             "same key => exactly one reservation" into a database invariant, not an application hope.
--  * user_show_holds : per-user, per-show seat counter, changed only through a guarded upsert
--             (ON CONFLICT ... DO UPDATE ... WHERE count + n <= limit). The row lock it takes also
--             serialises concurrent requests of the same user, which is what makes the limit race-free.
--
-- Deliberate omissions:
--  * No foreign keys from reservations / user_show_holds / seats.reservation_id. Thousands of concurrent
--    inserts that FK-check one parent row all take KEY SHARE locks on it and churn MultiXact state;
--    that is pure overhead on the hottest path. Integrity is enforced by the transaction logic and
--    verified by the reconciliation tests instead. (seats -> shows keeps its FK: it is only checked
--    once, when a show is created.)
--  * No extra indexes: every extra index is write amplification on the stampede path.

CREATE TABLE shows (
    id              uuid         PRIMARY KEY,
    name            text         NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    price_paise     bigint       NOT NULL CHECK (price_paise >= 0),
    per_user_limit  integer      NOT NULL CHECK (per_user_limit BETWEEN 1 AND 100),
    total_seats     integer      NOT NULL CHECK (total_seats > 0),
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX shows_created_at_idx ON shows (created_at DESC);

-- fillfactor 70 leaves room on each page so that seat status flips are HOT updates
-- (no index maintenance, far less bloat): none of the updated columns is indexed.
CREATE TABLE seats (
    show_id         uuid         NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    label           text         NOT NULL,
    ord             integer      NOT NULL,                 -- creation order; also the deterministic lock order
    status          text         NOT NULL DEFAULT 'available'
                    CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id  uuid,
    user_id         text,
    PRIMARY KEY (show_id, label),
    CONSTRAINT seats_owner_consistent CHECK (
        (status = 'available' AND reservation_id IS NULL AND user_id IS NULL)
     OR (status <> 'available' AND reservation_id IS NOT NULL AND user_id IS NOT NULL)
    )
) WITH (fillfactor = 70);

CREATE TABLE reservations (
    id               uuid         PRIMARY KEY,
    show_id          uuid         NOT NULL,
    user_id          text         NOT NULL,
    idempotency_key  text         NOT NULL,
    request_hash     text         NOT NULL,                -- sha-256 of (show_id, sorted seat labels)
    seats            text[]       NOT NULL,                -- in the order the client asked for them
    amount_paise     bigint       NOT NULL CHECK (amount_paise >= 0),
    status           text         NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    cancelled_at     timestamptz,
    CONSTRAINT reservations_idempotency_uk UNIQUE (user_id, idempotency_key)
);

CREATE TABLE user_show_holds (
    show_id     uuid     NOT NULL,
    user_id     text     NOT NULL,
    seat_count  integer  NOT NULL CHECK (seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
) WITH (fillfactor = 70);
