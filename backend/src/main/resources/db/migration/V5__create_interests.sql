-- V5: Educational interests (module `user`, docs/plans/week-02.md §6).
--
-- - `interests` is reference data, maintained only by migrations. The code is the stable key that
--   clients send; `sort_order` is the display order (`GET /api/v1/interests` sorts by it, then by
--   code). An interest that is no longer offered is deactivated, never deleted: users may still
--   reference it, and the foreign key below rejects the deletion.
-- - `user_interests` holds each user's selection (many-to-many, assigned from W2-10): the primary key
--   prevents duplicates, the foreign keys unknown users or codes; deleting a user deletes the rows.
--   The limit of 8 per user and the rejection of inactive codes are checked by the application.
-- - The seed data has fixed codes and orders, so every database has the same catalogue.
CREATE TABLE interests (
    code        VARCHAR(40)  PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    description VARCHAR(255) NOT NULL,
    sort_order  SMALLINT     NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_interests_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE user_interests (
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    interest_code VARCHAR(40) NOT NULL REFERENCES interests (code),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, interest_code)
);

INSERT INTO interests (code, name, description, sort_order) VALUES
    ('PROBABILITY',        'Probability & odds',      'How likely outcomes are and how odds are expressed',                      10),
    ('MATHEMATICS',        'Mathematics of games',    'Expected value, house edge and variance',                                 20),
    ('STATISTICS',         'Statistics & data',       'Reading results, randomness and the law of large numbers',                30),
    ('GAME_THEORY',        'Game theory & strategy',  'Decision-making under uncertainty',                                       40),
    ('PSYCHOLOGY',         'Psychology of decisions', 'Cognitive biases such as the gambler''s fallacy',                         50),
    ('FINANCIAL_LITERACY', 'Financial literacy',      'Budgeting, risk and personal finance basics',                             60),
    ('HISTORY',            'History of games',        'Origins and evolution of casino games',                                   70),
    ('RESPONSIBLE_PLAY',   'Responsible play',        'Healthy habits, limits and self-awareness (informational, not clinical)', 80);
