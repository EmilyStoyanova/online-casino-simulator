-- V6: Wallets (one per PLAYER) and their append-only ledger (ADR-0003, docs/plans/week-03.md §6.2).
--
-- Coins are BIGINT, never floating point. Every balance and amount is capped at 2^53 - 1
-- (9007199254740991), so every value is exact in JavaScript clients (Number.MAX_SAFE_INTEGER).
-- Only the application creates wallets, and only for players: a CHECK cannot read users.role.
-- Schema only. Wallets for accounts that existed before this migration follow in V7.

CREATE TABLE wallets (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    balance    BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallets_user_id UNIQUE (user_id),
    -- RESTRICT, not CASCADE: an account with a wallet cannot be deleted (account deletion: Week 11).
    CONSTRAINT fk_wallets_user    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_wallets_balance CHECK (balance BETWEEN 0 AND 9007199254740991)
);

CREATE TRIGGER trg_wallets_updated_at BEFORE UPDATE ON wallets
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Append-only ledger. Entries of one wallet are inserted only while that wallet's row is locked
-- (SELECT ... FOR UPDATE), so their ids increase in commit order per wallet: the history is ordered
-- and paged by id. Rolled-back inserts consume ids, so ids can have gaps.
CREATE TABLE wallet_transactions (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    wallet_id       UUID         NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    amount          BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    reference       VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_wallet_transactions_wallet      FOREIGN KEY (wallet_id) REFERENCES wallets (id) ON DELETE RESTRICT,
    -- One operation per key and wallet: a retried operation finds its first result.
    CONSTRAINT uq_wallet_transactions_idempotency UNIQUE (wallet_id, idempotency_key),
    CONSTRAINT ck_wallet_transactions_amount      CHECK (amount <> 0 AND amount BETWEEN -9007199254740991 AND 9007199254740991),
    CONSTRAINT ck_wallet_transactions_balance     CHECK (balance_after BETWEEN 0 AND 9007199254740991),
    -- Each week adds its types and direction rules in its own migration.
    CONSTRAINT ck_wallet_transactions_type        CHECK (type IN ('STARTING_BALANCE')),
    CONSTRAINT ck_wallet_transactions_direction   CHECK (type <> 'STARTING_BALANCE' OR amount > 0),
    -- Keys are chosen by the calling module (e.g. 'starting-balance'), never raw client input.
    CONSTRAINT ck_wallet_transactions_key         CHECK (idempotency_key ~ '^[a-z0-9][a-z0-9:._-]{0,99}$')
);

-- History: newest first per wallet (keyset pagination on id).
CREATE INDEX ix_wallet_transactions_wallet_id_id ON wallet_transactions (wallet_id, id DESC);

-- Committed entries are never changed or removed. Rows of a rolled-back transaction are simply never
-- visible (no DELETE happens), so rollbacks are unaffected. TRUNCATE (statement level) is not covered;
-- only the guarded test cleanup uses it (docs/plans/week-03.md §6.4, §6.5).
CREATE FUNCTION reject_ledger_change()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    RAISE EXCEPTION 'wallet_transactions is append-only: % rejected', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

COMMENT ON FUNCTION reject_ledger_change() IS
    'Trigger function that rejects every UPDATE and DELETE of wallet_transactions (append-only ledger).';

CREATE TRIGGER trg_wallet_transactions_append_only BEFORE UPDATE OR DELETE ON wallet_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();

-- A wallet always belongs to the same user.
CREATE FUNCTION reject_wallet_owner_change()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF NEW.user_id <> OLD.user_id THEN
        RAISE EXCEPTION 'wallets.user_id cannot change' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION reject_wallet_owner_change() IS
    'Trigger function that keeps wallets.user_id unchanged.';

CREATE TRIGGER trg_wallets_owner_fixed BEFORE UPDATE OF user_id ON wallets
    FOR EACH ROW EXECUTE FUNCTION reject_wallet_owner_change();
