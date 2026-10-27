-- V1: Baseline shared database objects.
--
-- Domain tables are introduced by the module that owns them (users in Week 2,
-- wallets in Week 3, ...). This baseline only provides reusable helpers.

-- Keeps `updated_at` columns correct for every UPDATE, including updates that do
-- not go through JPA (manual fixes, batch jobs). Attached per table with:
--   CREATE TRIGGER trg_<table>_updated_at BEFORE UPDATE ON <table>
--       FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE OR REPLACE FUNCTION set_updated_at()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION set_updated_at() IS
    'Trigger function that sets updated_at to the current timestamp on UPDATE.';
