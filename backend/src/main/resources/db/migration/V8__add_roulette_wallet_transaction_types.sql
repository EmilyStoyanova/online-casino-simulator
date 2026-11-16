-- Week 4 ledger types (docs/plans/week-04.md §5.2): the stake of a roulette round (a debit) and its
-- payout (a credit). Replaces the V6 type list and direction rule; nothing else changes.
--
--   STARTING_BALANCE  amount > 0  (unchanged)
--   ROULETTE_STAKE    amount < 0
--   ROULETTE_PAYOUT   amount > 0
--
-- Every existing row (STARTING_BALANCE with amount > 0) satisfies the new rules, so no row changes.
-- Unchanged: amount never 0 and within +/- (2^53 - 1) (ck_wallet_transactions_amount), the balance
-- bounds, the key format, the unique idempotency key per wallet, the foreign keys and both triggers
-- (append-only ledger, fixed wallet owner). The type list stays the only place that names the
-- allowed types: the direction rule constrains only the sign of the listed types, so an unknown type
-- is reported by ck_wallet_transactions_type.
--
-- One ALTER TABLE: the table lock is taken once, briefly, while the existing rows are validated.
-- Flyway runs it in one transaction: if any part fails, the V6 constraints stay in place.

ALTER TABLE wallet_transactions
    DROP CONSTRAINT ck_wallet_transactions_type,
    DROP CONSTRAINT ck_wallet_transactions_direction,
    ADD CONSTRAINT ck_wallet_transactions_type
        CHECK (type IN ('STARTING_BALANCE', 'ROULETTE_STAKE', 'ROULETTE_PAYOUT')),
    ADD CONSTRAINT ck_wallet_transactions_direction
        CHECK ((type NOT IN ('STARTING_BALANCE', 'ROULETTE_PAYOUT') OR amount > 0)
           AND (type <> 'ROULETTE_STAKE' OR amount < 0));
