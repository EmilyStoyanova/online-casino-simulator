-- V3: User accounts (module `user`, docs/plans/week-02.md §6).
--
-- - UUID ids, so account ids cannot be enumerated.
-- - Emails are stored trimmed and lower-cased (normalised by the application); together with the
--   unique constraint this makes them unique regardless of case.
-- - `password_hash` holds only the encoded hash of Spring's DelegatingPasswordEncoder, e.g.
--   `{bcrypt}$2a$12$…`; the `{id}` prefix is required, so a plain-text password is rejected.
-- - One `role` (the roles are exclusive), `status` for disabling accounts, `version` for optimistic
--   locking. No date of birth or IP address (data minimisation); 18+ is a confirmed timestamp.
CREATE TABLE users (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email              VARCHAR(254) NOT NULL,
    password_hash      VARCHAR(100) NOT NULL,
    display_name       VARCHAR(30)  NOT NULL,
    role               VARCHAR(20)  NOT NULL DEFAULT 'PLAYER',
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    adult_confirmed_at TIMESTAMPTZ  NOT NULL,
    last_login_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email                 UNIQUE (email),
    CONSTRAINT ck_users_email_normalized      CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_users_password_hash_encoded CHECK (password_hash ~ '^\{[^{}]+\}.+$'),
    CONSTRAINT ck_users_role                  CHECK (role IN ('PLAYER', 'ADMIN')),
    CONSTRAINT ck_users_status                CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
