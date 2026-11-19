-- Read-only reconciliation of roulette rounds with the wallet ledger AT V9 (docs/plans/week-04.md
-- §5.5, §5.7 and §5.8, docs/database.md "Roulette rounds and bets (V9)").
-- It checks what the database does not enforce: that every round's ledger entries are its own
-- (player, type, amount, reference, order), that the bets add up and pay what the winning number pays,
-- and that no roulette ledger entry exists without exactly one round.
-- Run only within an approved rollout, after verify-migrations.sql reported V9:
--   infrastructure/neon/readonly-query.sh < infrastructure/neon/verify-roulette.sql
-- readonly-query.sh wraps it in a READ ONLY transaction that is rolled back. Counts only: no row
-- contents are read. Valid with empty tables (every count 0). Every comparison is NULL-safe
-- (IS DISTINCT FROM, IS NOT TRUE, LEFT JOIN): a missing value is counted as a violation, never skipped.
-- MigrationCompatibilityIT runs it on PostgreSQL 17 and 18 (with deliberately inconsistent rows), and
-- RouletteConcurrencyIT and RoundLedgerIntegrityIT on rounds played by RouletteService.

\conninfo

-- 1. Expected: 0 in every column.
--    rounds_without_1_to_10_bets            a round has no bet or more than 10
--    rounds_with_totals_not_matching_bets   total_stake or total_payout differs from the sum of its bets
--    bets_with_wrong_payout                 a bet's payout is not what the winning number pays (European
--                                           rules: 36x, 2x or 3x the amount on a win, 0 on a loss, zero
--                                           wins only a straight bet on 0)
--    rounds_with_missing_entries            no stake entry, or a payout entry missing although something
--                                           was won (also enforced by V9's foreign keys and CHECKs)
--    entries_of_another_player              a stake or payout entry in another player's wallet
--    entries_with_wrong_type                the stake entry is not ROULETTE_STAKE or the payout entry not
--                                           ROULETTE_PAYOUT
--    entries_with_wrong_amount              stake amount <> -total_stake or payout amount <> total_payout
--    entries_with_wrong_reference           reference <> round id, or key <> roulette-round:<id>:stake|payout
--    payouts_not_after_their_stake          the payout entry is not newer than the stake entry
--    rounds_with_wrong_balance_after        balance_after <> balance_after of the round's last entry
--    roulette_entries_not_in_exactly_one_round
--                                           a ROULETTE_STAKE or ROULETTE_PAYOUT entry referenced by no
--                                           round, or by more than one (as stake of one, payout of another)
WITH round_entries AS (
    SELECT r.id, r.user_id, r.winning_number, r.total_stake, r.total_payout, r.balance_after,
           r.payout_transaction_id,
           s.id AS stake_id, s.type AS stake_type, s.amount AS stake_amount, s.reference AS stake_reference,
           s.idempotency_key AS stake_key, s.balance_after AS stake_balance_after, sw.user_id AS stake_owner,
           p.id AS payout_id, p.type AS payout_type, p.amount AS payout_amount, p.reference AS payout_reference,
           p.idempotency_key AS payout_key, p.balance_after AS payout_balance_after, pw.user_id AS payout_owner
    FROM roulette_rounds r
    LEFT JOIN wallet_transactions s ON s.id = r.stake_transaction_id
    LEFT JOIN wallets sw ON sw.id = s.wallet_id
    LEFT JOIN wallet_transactions p ON p.id = r.payout_transaction_id
    LEFT JOIN wallets pw ON pw.id = p.wallet_id
),
bet_totals AS (
    SELECT r.id, count(b.id) AS bets, sum(b.amount) AS stake, sum(b.payout) AS payout
    FROM roulette_rounds r
    LEFT JOIN roulette_bets b ON b.round_id = r.id
    GROUP BY r.id
),
bet_results AS (
    SELECT b.payout,
           CASE CASE b.category
                    WHEN 'STRAIGHT'  THEN b.number = r.winning_number
                    WHEN 'RED_BLACK' THEN r.winning_number <> 0
                                          AND (r.winning_number IN (1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36))
                                              = (b.selection = 'RED')
                    WHEN 'EVEN_ODD'  THEN r.winning_number <> 0 AND (r.winning_number % 2 = 0) = (b.selection = 'EVEN')
                    WHEN 'LOW_HIGH'  THEN r.winning_number <> 0 AND (r.winning_number <= 18) = (b.selection = 'LOW')
                    WHEN 'DOZEN'     THEN r.winning_number <> 0
                                          AND (r.winning_number - 1) / 12
                                              = CASE b.selection WHEN 'FIRST' THEN 0 WHEN 'SECOND' THEN 1 WHEN 'THIRD' THEN 2 END
                END
               WHEN true  THEN b.amount * CASE b.category WHEN 'STRAIGHT' THEN 36 WHEN 'DOZEN' THEN 3 ELSE 2 END
               WHEN false THEN 0
           END AS expected_payout
    FROM roulette_bets b
    JOIN roulette_rounds r ON r.id = b.round_id
)
SELECT
    (SELECT count(*) FROM bet_totals WHERE bets NOT BETWEEN 1 AND 10)
        AS rounds_without_1_to_10_bets,
    (SELECT count(*) FROM round_entries e JOIN bet_totals t ON t.id = e.id
     WHERE e.total_stake IS DISTINCT FROM t.stake OR e.total_payout IS DISTINCT FROM t.payout)
        AS rounds_with_totals_not_matching_bets,
    (SELECT count(*) FROM bet_results WHERE payout IS DISTINCT FROM expected_payout)
        AS bets_with_wrong_payout,
    (SELECT count(*) FROM round_entries
     WHERE stake_id IS NULL
        OR (total_payout > 0) IS DISTINCT FROM (payout_transaction_id IS NOT NULL)
        OR (payout_transaction_id IS NOT NULL AND payout_id IS NULL))
        AS rounds_with_missing_entries,
    (SELECT count(*) FROM round_entries WHERE stake_owner IS DISTINCT FROM user_id)
    + (SELECT count(*) FROM round_entries WHERE payout_transaction_id IS NOT NULL AND payout_owner IS DISTINCT FROM user_id)
        AS entries_of_another_player,
    (SELECT count(*) FROM round_entries WHERE stake_type IS DISTINCT FROM 'ROULETTE_STAKE')
    + (SELECT count(*) FROM round_entries WHERE payout_transaction_id IS NOT NULL AND payout_type IS DISTINCT FROM 'ROULETTE_PAYOUT')
        AS entries_with_wrong_type,
    (SELECT count(*) FROM round_entries WHERE stake_amount IS DISTINCT FROM -total_stake)
    + (SELECT count(*) FROM round_entries WHERE payout_transaction_id IS NOT NULL AND payout_amount IS DISTINCT FROM total_payout)
        AS entries_with_wrong_amount,
    (SELECT count(*) FROM round_entries
     WHERE stake_reference IS DISTINCT FROM id::text OR stake_key IS DISTINCT FROM 'roulette-round:' || id || ':stake')
    + (SELECT count(*) FROM round_entries
       WHERE payout_transaction_id IS NOT NULL
         AND (payout_reference IS DISTINCT FROM id::text OR payout_key IS DISTINCT FROM 'roulette-round:' || id || ':payout'))
        AS entries_with_wrong_reference,
    (SELECT count(*) FROM round_entries WHERE payout_transaction_id IS NOT NULL AND (payout_id > stake_id) IS NOT TRUE)
        AS payouts_not_after_their_stake,
    (SELECT count(*) FROM round_entries
     WHERE balance_after IS DISTINCT FROM CASE WHEN payout_transaction_id IS NULL THEN stake_balance_after ELSE payout_balance_after END)
        AS rounds_with_wrong_balance_after,
    (SELECT count(*) FROM wallet_transactions t
     WHERE t.type IN ('ROULETTE_STAKE', 'ROULETTE_PAYOUT')
       AND (SELECT count(*) FROM roulette_rounds r WHERE r.stake_transaction_id = t.id OR r.payout_transaction_id = t.id) <> 1)
        AS roulette_entries_not_in_exactly_one_round;

-- 2. For the record. Expected: stake_entries = rounds and payout_entries = winning_rounds (every round has
--    one stake entry, every winning round one payout entry, and there are no others).
SELECT
    (SELECT count(*) FROM roulette_rounds) AS rounds,
    (SELECT count(*) FROM roulette_bets) AS bets,
    (SELECT count(DISTINCT user_id) FROM roulette_rounds) AS players_with_rounds,
    (SELECT count(*) FROM roulette_rounds WHERE total_payout > 0) AS winning_rounds,
    (SELECT count(*) FROM wallet_transactions WHERE type = 'ROULETTE_STAKE') AS stake_entries,
    (SELECT count(*) FROM wallet_transactions WHERE type = 'ROULETTE_PAYOUT') AS payout_entries;
