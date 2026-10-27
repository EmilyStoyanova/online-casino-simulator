# User scenarios

Primary user flows of the MVP. They drive acceptance criteria and, from Week 11, the end-to-end
test suite. Actors: **Player** (adult user, role `PLAYER`), **Administrator** (role `ADMIN`).

## Player

### S1 — Create an account and receive starting coins
1. The player registers with email, display name and password and confirms they are an adult.
2. The system stores a securely hashed password and creates a wallet with the starting balance.
3. The player is signed in and sees the virtual balance.

**Acceptance:** the starting balance is credited exactly once and appears in the transaction history.

### S2 — Choose educational interests
1. The player opens the profile and selects interests (e.g. probability, finance, history).
2. The learning library prioritises modules matching those interests.

### S3 — Play European roulette
1. The player places one or more virtual-coin bets (straight number, red/black, even/odd,
   low/high, dozens) and confirms.
2. The backend checks balance, bet limits and play limits, draws one of 37 outcomes (0–36) with a
   cryptographically secure RNG, calculates payouts and settles the wallet atomically.
3. The frontend animates the wheel to the **server-provided** number and shows the win/loss.

**Acceptance:** retrying the same request (same idempotency key) never settles a round twice;
a balance never becomes negative.

### S4 — Review history and statistics
The player views round history, wallet transactions, total rounds, net virtual result, time played
and the house edge of each bet type. Figures match the recorded transactions.

### S5 — Learn and earn virtual coins
1. The player studies an educational module and submits the quiz.
2. The backend validates the answers; a passing, first-time, within-daily-limit attempt grants a reward.

**Acceptance:** rewards are never duplicated and never depend on watching third-party (e.g. YouTube)
videos. A separate free balance-restore option exists that requires no learning activity.

### S6 — Set limits, take a break, self-exclude
1. The player sets a session duration limit, takes a break or self-excludes for a period.
2. While active, the backend rejects new game actions, regardless of the client.

### S7 — Leave and return
The player logs out and later logs back in; profile, balance and history are unchanged.

## Administrator

### S8 — Manage content and rules
The administrator manages learning modules and quizzes, configures rewards, daily limits and play
limits, reviews platform statistics and audit events. All admin endpoints require the `ADMIN` role.

## Explicitly out of scope
Real-money transactions or purchases of virtual coins, cash-convertible rewards or crypto,
multiplayer, live dealers, other casino games (planned post-MVP), leaderboards or competitive
mechanics that encourage excessive play, native mobile apps.
