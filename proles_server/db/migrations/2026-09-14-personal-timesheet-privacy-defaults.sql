-- Personal pink timesheet and notification privacy defaults
ALTER TABLE notification_preferences
    ADD COLUMN IF NOT EXISTS privacy_defaults_configured BOOLEAN NOT NULL DEFAULT FALSE;

-- New users / new rows are expected to start with privacy notifications ON.
ALTER TABLE notification_preferences
    ALTER COLUMN trip_visible_to_all SET DEFAULT TRUE,
    ALTER COLUMN trip_telegram_broadcast SET DEFAULT TRUE;

-- Existing rows created by the previous release had these two fields disabled
-- because the old defaults were FALSE. Mark them as migrated so the API will
-- present the new defaults as ON until the user explicitly saves the settings.
-- The application uses privacy_defaults_configured for this one-time transition.
UPDATE notification_preferences
SET privacy_defaults_configured = FALSE
WHERE privacy_defaults_configured IS NULL;

CREATE TABLE IF NOT EXISTS personal_timesheet_tasks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    year INTEGER NOT NULL,
    month INTEGER NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    name VARCHAR(255) NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    hours DOUBLE PRECISION NOT NULL DEFAULT 0,
    category VARCHAR(100) NOT NULL DEFAULT '',
    status VARCHAR(50) NOT NULL DEFAULT 'Not started',
    is_month_task BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS personal_timesheet_period_idx
    ON personal_timesheet_tasks(user_id, year, month);
