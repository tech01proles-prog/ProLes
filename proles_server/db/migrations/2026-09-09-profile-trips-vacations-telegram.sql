ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(30) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS telegram_username VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS birth_date DATE;

CREATE TABLE IF NOT EXISTS positions (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL UNIQUE,
    parent_id UUID REFERENCES positions(id) ON DELETE SET NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_positions_parent ON positions(parent_id);
ALTER TABLE users ADD COLUMN IF NOT EXISTS position_id UUID REFERENCES positions(id) ON DELETE SET NULL;

ALTER TABLE business_trips ADD COLUMN IF NOT EXISTS project_number VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE business_trips ADD COLUMN IF NOT EXISTS company_name VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE business_trips ADD COLUMN IF NOT EXISTS country VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE business_trips ALTER COLUMN project_id DROP NOT NULL;

ALTER TABLE vacations ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE vacations ADD COLUMN IF NOT EXISTS approved_by UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE vacations ADD COLUMN IF NOT EXISTS approved_at BIGINT;
ALTER TABLE vacations ADD COLUMN IF NOT EXISTS rejection_reason TEXT NOT NULL DEFAULT '';
UPDATE vacations SET status='APPROVED' WHERE status='PENDING';

ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS ticket_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS trip_visible_to_all BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS trip_telegram_broadcast BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS trip_change_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS vacation_decision_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS expense_created_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS ticket_receipt_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS telegram_link_code VARCHAR(20) UNIQUE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS telegram_linked_at BIGINT;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS telegram_chat_id VARCHAR(100) UNIQUE;
ALTER TABLE notification_preferences ADD COLUMN IF NOT EXISTS telegram_linked_username VARCHAR(100) NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS telegram_links (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    chat_id VARCHAR(100) NOT NULL UNIQUE,
    username VARCHAR(100) NOT NULL DEFAULT '',
    linked_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_users_position_id ON users(position_id);
CREATE INDEX IF NOT EXISTS idx_business_trips_country ON business_trips(country);
CREATE INDEX IF NOT EXISTS idx_notifications_target_created ON notifications(target_user_id, created_at DESC);
