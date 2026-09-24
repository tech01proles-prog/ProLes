-- ProLes platform upgrade
-- Base commit: a444df4f70a9408a4dc0a90b17705956dd835ad2
-- PostgreSQL 16+
--
-- Запускать миграцию один раз перед развёртыванием обновлённого сервера.
-- Все изменения совместимы со старым приложением: существующие ключевые поля
-- не удаляются, а новые внешние ключи остаются nullable там, где это требуется.

BEGIN;

-- ============================================================
-- 1. USERS: признак дистанционного сотрудника
-- ============================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS is_remote BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.is_remote IS
    'TRUE для дистанционного сотрудника. Налоговая стоимость: earned / 0.87; иначе earned * 1.37';

CREATE INDEX IF NOT EXISTS idx_users_is_remote
    ON users (is_remote);

ALTER TABLE expenses
    ALTER COLUMN project_id DROP NOT NULL;

-- ============================================================
-- 2. SUBPROJECTS: подпроекты внутри существующих проектов
-- ============================================================

CREATE TABLE IF NOT EXISTS subprojects (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL
        REFERENCES projects(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(100) NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0,
    archived_at BIGINT,
    CONSTRAINT chk_subprojects_name_not_blank
        CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_subprojects_project_name_ci
    ON subprojects (project_id, lower(name));

CREATE UNIQUE INDEX IF NOT EXISTS uq_subprojects_project_code_ci
    ON subprojects (project_id, lower(code))
    WHERE btrim(code) <> '';

CREATE INDEX IF NOT EXISTS idx_subprojects_project_active_sort
    ON subprojects (project_id, is_active, sort_order, name);

COMMENT ON TABLE subprojects IS
    'Подпроекты существующих проектов';

COMMENT ON COLUMN subprojects.archived_at IS
    'Unix time в миллисекундах; NULL означает, что подпроект не архивирован';

-- ============================================================
-- 3. Связи существующих сущностей с подпроектами
-- ============================================================

ALTER TABLE time_entries
    ADD COLUMN IF NOT EXISTS subproject_id UUID;

ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS subproject_id UUID;

ALTER TABLE incomes
    ADD COLUMN IF NOT EXISTS subproject_id UUID;

ALTER TABLE business_trips
    ADD COLUMN IF NOT EXISTS subproject_id UUID;

ALTER TABLE tickets
    ADD COLUMN IF NOT EXISTS subproject_id UUID;

ALTER TABLE salary_components
    ADD COLUMN IF NOT EXISTS subproject_id UUID;

DO $$BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_time_entries_subproject'
    ) THEN
        ALTER TABLE time_entries
            ADD CONSTRAINT fk_time_entries_subproject
            FOREIGN KEY (subproject_id)
            REFERENCES subprojects(id)
            ON UPDATE CASCADE
            ON DELETE SET NULL
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_expenses_subproject'
    ) THEN
        ALTER TABLE expenses
            ADD CONSTRAINT fk_expenses_subproject
            FOREIGN KEY (subproject_id)
            REFERENCES subprojects(id)
            ON UPDATE CASCADE
            ON DELETE SET NULL
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_incomes_subproject'
    ) THEN
        ALTER TABLE incomes
            ADD CONSTRAINT fk_incomes_subproject
            FOREIGN KEY (subproject_id)
            REFERENCES subprojects(id)
            ON UPDATE CASCADE
            ON DELETE SET NULL
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_business_trips_subproject'
    ) THEN
        ALTER TABLE business_trips
            ADD CONSTRAINT fk_business_trips_subproject
            FOREIGN KEY (subproject_id)
            REFERENCES subprojects(id)
            ON UPDATE CASCADE
            ON DELETE SET NULL
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_tickets_subproject'
    ) THEN
        ALTER TABLE tickets
            ADD CONSTRAINT fk_tickets_subproject
            FOREIGN KEY (subproject_id)
            REFERENCES subprojects(id)
            ON UPDATE CASCADE
            ON DELETE SET NULL
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_salary_components_subproject'
    ) THEN
        ALTER TABLE salary_components
            ADD CONSTRAINT fk_salary_components_subproject
            FOREIGN KEY (subproject_id)
            REFERENCES subprojects(id)
            ON UPDATE CASCADE
            ON DELETE SET NULL
            NOT VALID;
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_time_entries_project_subproject_date
    ON time_entries (project_id, subproject_id, date);

CREATE INDEX IF NOT EXISTS idx_time_entries_user_project_subproject_date
    ON time_entries (user_id, project_id, subproject_id, date);

CREATE INDEX IF NOT EXISTS idx_expenses_project_subproject_date
    ON expenses (project_id, subproject_id, date);

CREATE INDEX IF NOT EXISTS idx_incomes_project_subproject_date
    ON incomes (project_id, subproject_id, date);

CREATE INDEX IF NOT EXISTS idx_business_trips_project_subproject
    ON business_trips (project_id, subproject_id);

CREATE INDEX IF NOT EXISTS idx_tickets_project_subproject
    ON tickets (project_id, subproject_id);

CREATE INDEX IF NOT EXISTS idx_salary_components_project_subproject
    ON salary_components (project_id, subproject_id);

-- ============================================================
-- 4. Защита от выбора подпроекта другого проекта
-- ============================================================

CREATE OR REPLACE FUNCTION enforce_subproject_belongs_to_project()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$DECLARE
    expected_project_id UUID;
BEGIN
    IF NEW.subproject_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.project_id IS NULL THEN
        RAISE EXCEPTION
            'Нельзя выбрать подпроект без проекта'
            USING ERRCODE = '23514';
    END IF;

    SELECT s.project_id
      INTO expected_project_id
      FROM subprojects s
     WHERE s.id = NEW.subproject_id;

    IF expected_project_id IS NULL THEN
        RAISE EXCEPTION
            'Подпроект % не найден',
            NEW.subproject_id
            USING ERRCODE = '23503';
    END IF;

    IF expected_project_id <> NEW.project_id THEN
        RAISE EXCEPTION
            'Подпроект % не принадлежит проекту %',
            NEW.subproject_id,
            NEW.project_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;$$;

DROP TRIGGER IF EXISTS trg_time_entries_subproject_project
    ON time_entries;
CREATE TRIGGER trg_time_entries_subproject_project
BEFORE INSERT OR UPDATE OF project_id, subproject_id
ON time_entries
FOR EACH ROW
EXECUTE FUNCTION enforce_subproject_belongs_to_project();

DROP TRIGGER IF EXISTS trg_expenses_subproject_project
    ON expenses;
CREATE TRIGGER trg_expenses_subproject_project
BEFORE INSERT OR UPDATE OF project_id, subproject_id
ON expenses
FOR EACH ROW
EXECUTE FUNCTION enforce_subproject_belongs_to_project();

DROP TRIGGER IF EXISTS trg_incomes_subproject_project
    ON incomes;
CREATE TRIGGER trg_incomes_subproject_project
BEFORE INSERT OR UPDATE OF project_id, subproject_id
ON incomes
FOR EACH ROW
EXECUTE FUNCTION enforce_subproject_belongs_to_project();

DROP TRIGGER IF EXISTS trg_business_trips_subproject_project
    ON business_trips;
CREATE TRIGGER trg_business_trips_subproject_project
BEFORE INSERT OR UPDATE OF project_id, subproject_id
ON business_trips
FOR EACH ROW
EXECUTE FUNCTION enforce_subproject_belongs_to_project();

DROP TRIGGER IF EXISTS trg_tickets_subproject_project
    ON tickets;
CREATE TRIGGER trg_tickets_subproject_project
BEFORE INSERT OR UPDATE OF project_id, subproject_id
ON tickets
FOR EACH ROW
EXECUTE FUNCTION enforce_subproject_belongs_to_project();

DROP TRIGGER IF EXISTS trg_salary_components_subproject_project
    ON salary_components;
CREATE TRIGGER trg_salary_components_subproject_project
BEFORE INSERT OR UPDATE OF project_id, subproject_id
ON salary_components
FOR EACH ROW
EXECUTE FUNCTION enforce_subproject_belongs_to_project();

-- ============================================================
-- 5. ТНПА: сертификационная документация проекта/подпроекта
-- ============================================================

CREATE TABLE IF NOT EXISTS tnpa_documents (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL
        REFERENCES projects(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    subproject_id UUID
        REFERENCES subprojects(id)
        ON UPDATE CASCADE
        ON DELETE SET NULL,
    original_name VARCHAR(500) NOT NULL,
    stored_name VARCHAR(500) NOT NULL,
    storage_path TEXT NOT NULL,
    mime_type VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream',
    size_bytes BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    uploaded_by UUID NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,
    uploaded_at BIGINT NOT NULL,
    deleted_at BIGINT,
    deleted_by UUID
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE SET NULL,
    CONSTRAINT chk_tnpa_original_name_not_blank
        CHECK (btrim(original_name) <> ''),
    CONSTRAINT chk_tnpa_stored_name_not_blank
        CHECK (btrim(stored_name) <> ''),
    CONSTRAINT chk_tnpa_storage_path_not_blank
        CHECK (btrim(storage_path) <> ''),
    CONSTRAINT chk_tnpa_size_nonnegative
        CHECK (size_bytes >= 0),
    CONSTRAINT chk_tnpa_sha256
        CHECK (checksum_sha256 ~ '^[0-9a-fA-F]{64}$')
);

CREATE INDEX IF NOT EXISTS idx_tnpa_project_subproject_uploaded
    ON tnpa_documents (project_id, subproject_id, uploaded_at DESC);

CREATE INDEX IF NOT EXISTS idx_tnpa_active_documents
    ON tnpa_documents (project_id, subproject_id)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_tnpa_storage_path
    ON tnpa_documents (storage_path);

DROP TRIGGER IF EXISTS trg_tnpa_subproject_project
    ON tnpa_documents;
CREATE TRIGGER trg_tnpa_subproject_project
BEFORE INSERT OR UPDATE OF project_id, subproject_id
ON tnpa_documents
FOR EACH ROW
EXECUTE FUNCTION enforce_subproject_belongs_to_project();

-- ============================================================
-- 6. Доходы/расходы: WITH_RECEIPT и WITHOUT_RECEIPT
-- ============================================================

ALTER TABLE expenses
    ALTER COLUMN category SET DEFAULT 'WITH_RECEIPT';

ALTER TABLE incomes
    ALTER COLUMN category SET DEFAULT 'WITH_RECEIPT';

UPDATE expenses
   SET category = CASE upper(btrim(category))
       WHEN 'WORK' THEN 'WITH_RECEIPT'
       WHEN 'PERSONAL' THEN 'WITHOUT_RECEIPT'
       WHEN 'WITH_RECEIPT' THEN 'WITH_RECEIPT'
       WHEN 'WITHOUT_RECEIPT' THEN 'WITHOUT_RECEIPT'
       ELSE 'WITH_RECEIPT'
   END;

UPDATE incomes
   SET category = CASE upper(btrim(category))
       WHEN 'WORK' THEN 'WITH_RECEIPT'
       WHEN 'PERSONAL' THEN 'WITHOUT_RECEIPT'
       WHEN 'WITH_RECEIPT' THEN 'WITH_RECEIPT'
       WHEN 'WITHOUT_RECEIPT' THEN 'WITHOUT_RECEIPT'
       ELSE 'WITH_RECEIPT'
   END;

-- Для типа WITHOUT_RECEIPT чек не требуется и считается закрытым.
UPDATE expenses
   SET receipt_submitted = TRUE,
       has_receipt_photo = FALSE
 WHERE category = 'WITHOUT_RECEIPT';

-- Удаляем ранее прикреплённые фотографии у записей, которые после
-- однозначной миграции PERSONAL стали WITHOUT_RECEIPT.
DELETE FROM expense_receipts er
USING expenses e
WHERE er.expense_id = e.id
  AND e.category = 'WITHOUT_RECEIPT';

DO $$BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_expenses_receipt_category'
    ) THEN
        ALTER TABLE expenses
            ADD CONSTRAINT chk_expenses_receipt_category
            CHECK (
                category IN ('WITH_RECEIPT', 'WITHOUT_RECEIPT')
            ) NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_incomes_receipt_category'
    ) THEN
        ALTER TABLE incomes
            ADD CONSTRAINT chk_incomes_receipt_category
            CHECK (
                category IN ('WITH_RECEIPT', 'WITHOUT_RECEIPT')
            ) NOT VALID;
    END IF;
END$$;

CREATE OR REPLACE FUNCTION enforce_without_receipt_expense()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$BEGIN
    IF NEW.category = 'WITHOUT_RECEIPT' THEN
        NEW.receipt_submitted := TRUE;
        NEW.has_receipt_photo := FALSE;
    END IF;

    RETURN NEW;
END;$$;

DROP TRIGGER IF EXISTS trg_expenses_without_receipt
    ON expenses;
CREATE TRIGGER trg_expenses_without_receipt
BEFORE INSERT OR UPDATE OF category, receipt_submitted, has_receipt_photo
ON expenses
FOR EACH ROW
EXECUTE FUNCTION enforce_without_receipt_expense();

CREATE OR REPLACE FUNCTION reject_receipt_for_without_receipt_expense()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$DECLARE
    selected_category VARCHAR(20);
BEGIN
    SELECT category
      INTO selected_category
      FROM expenses
     WHERE id = NEW.expense_id;

    IF selected_category = 'WITHOUT_RECEIPT' THEN
        RAISE EXCEPTION
            'Для расхода без чека вложение чека запрещено'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;$$;

DROP TRIGGER IF EXISTS trg_expense_receipts_category_guard
    ON expense_receipts;
CREATE TRIGGER trg_expense_receipts_category_guard
BEFORE INSERT OR UPDATE OF expense_id
ON expense_receipts
FOR EACH ROW
EXECUTE FUNCTION reject_receipt_for_without_receipt_expense();

-- ============================================================
-- 7. Командировки: начало, необязательное окончание и статус
-- ============================================================

ALTER TABLE business_trips
    ADD COLUMN IF NOT EXISTS start_date DATE;

ALTER TABLE business_trips
    ADD COLUMN IF NOT EXISTS end_date DATE;

ALTER TABLE business_trips
    ADD COLUMN IF NOT EXISTS status VARCHAR(20)
        NOT NULL DEFAULT 'ACTIVE';

-- Текущее поле date сохраняется для обратной совместимости.
UPDATE business_trips
   SET start_date = date
 WHERE start_date IS NULL;

-- Существующие завершённые командировки переносятся в новую модель.
UPDATE business_trips
   SET end_date = completed_date
 WHERE end_date IS NULL
   AND completed_date IS NOT NULL;

UPDATE business_trips
   SET status = CASE
       WHEN COALESCE(end_date, completed_date) IS NOT NULL
           THEN 'COMPLETED'
       ELSE 'ACTIVE'
   END;

ALTER TABLE business_trips
    ALTER COLUMN start_date SET NOT NULL;

DO $$BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_business_trips_date_range'
    ) THEN
        ALTER TABLE business_trips
            ADD CONSTRAINT chk_business_trips_date_range
            CHECK (
                end_date IS NULL
                OR end_date >= start_date
            ) NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_business_trips_status'
    ) THEN
        ALTER TABLE business_trips
            ADD CONSTRAINT chk_business_trips_status
            CHECK (
                status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')
            ) NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_business_trips_completed_end_date'
    ) THEN
        ALTER TABLE business_trips
            ADD CONSTRAINT chk_business_trips_completed_end_date
            CHECK (
                status <> 'COMPLETED'
                OR end_date IS NOT NULL
            ) NOT VALID;
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_business_trips_user_status_start
    ON business_trips (user_id, status, start_date DESC);

-- ============================================================
-- 8. Зарплата: штрафы, удержания, погашение и баланс
-- ============================================================

ALTER TABLE salary_records
    ADD COLUMN IF NOT EXISTS penalty_amount DOUBLE PRECISION
        NOT NULL DEFAULT 0.0;

ALTER TABLE salary_records
    ADD COLUMN IF NOT EXISTS withholding_amount DOUBLE PRECISION
        NOT NULL DEFAULT 0.0;

ALTER TABLE salary_records
    ADD COLUMN IF NOT EXISTS withholding_repayment_amount DOUBLE PRECISION
        NOT NULL DEFAULT 0.0;

ALTER TABLE salary_records
    ADD COLUMN IF NOT EXISTS gross_amount DOUBLE PRECISION
        NOT NULL DEFAULT 0.0;

ALTER TABLE salary_records
    ADD COLUMN IF NOT EXISTS tax_inclusive_cost DOUBLE PRECISION
        NOT NULL DEFAULT 0.0;

ALTER TABLE salary_records
    ADD COLUMN IF NOT EXISTS remote_employee BOOLEAN
        NOT NULL DEFAULT FALSE;

-- Для существующих записей gross_amount равен сумме начислений до новых
-- штрафов и удержаний. total_amount остаётся фактической выплатой.
UPDATE salary_records
   SET gross_amount =
       fixed_amount
       + piece_amount
       + hourly_amount
       + bonus_amount
 WHERE gross_amount = 0.0;

UPDATE salary_records sr
   SET remote_employee = u.is_remote
  FROM users u
 WHERE u.id = sr.user_id;

UPDATE salary_records
   SET tax_inclusive_cost = CASE
       WHEN remote_employee
           THEN gross_amount / 0.87
       ELSE gross_amount * 1.37
   END
 WHERE tax_inclusive_cost = 0.0;

DO $$BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_salary_records_financial_amounts'
    ) THEN
        ALTER TABLE salary_records
            ADD CONSTRAINT chk_salary_records_financial_amounts
            CHECK (
                penalty_amount >= 0
                AND withholding_amount >= 0
                AND withholding_repayment_amount >= 0
                AND gross_amount >= 0
                AND tax_inclusive_cost >= 0
            ) NOT VALID;
    END IF;
END$$;

COMMENT ON COLUMN salary_records.remote_employee IS
    'Снимок признака дистанционного сотрудника на момент расчёта';

COMMENT ON COLUMN salary_records.tax_inclusive_cost IS
    'Расход компании с налогами: gross * 1.37 либо gross / 0.87 для remote';

CREATE TABLE IF NOT EXISTS employee_balance_transactions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,
    salary_record_id UUID
        REFERENCES salary_records(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,
    transaction_type VARCHAR(32) NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    comment TEXT NOT NULL DEFAULT '',
    created_by UUID NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,
    created_at BIGINT NOT NULL,
    reversed_transaction_id UUID
        REFERENCES employee_balance_transactions(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,
    idempotency_key VARCHAR(100),
    CONSTRAINT chk_employee_balance_type
        CHECK (
            transaction_type IN (
                'WITHHOLDING',
                'REPAYMENT',
                'ADJUSTMENT_INCREASE',
                'ADJUSTMENT_DECREASE',
                'REVERSAL'
            )
        ),
    CONSTRAINT chk_employee_balance_amount_positive
        CHECK (amount > 0),
    CONSTRAINT chk_employee_balance_not_self_reversed
        CHECK (
            reversed_transaction_id IS NULL
            OR reversed_transaction_id <> id
        )
);

CREATE INDEX IF NOT EXISTS idx_employee_balance_user_created
    ON employee_balance_transactions (user_id, created_at, id);

CREATE INDEX IF NOT EXISTS idx_employee_balance_salary_record
    ON employee_balance_transactions (salary_record_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_employee_balance_idempotency
    ON employee_balance_transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_employee_balance_reversal
    ON employee_balance_transactions (reversed_transaction_id)
    WHERE reversed_transaction_id IS NOT NULL;

CREATE OR REPLACE VIEW employee_balances AS
SELECT
    u.id AS user_id,
    COALESCE(
        SUM(
            CASE ebt.transaction_type
                WHEN 'WITHHOLDING' THEN ebt.amount
                WHEN 'ADJUSTMENT_INCREASE' THEN ebt.amount
                WHEN 'REPAYMENT' THEN -ebt.amount
                WHEN 'ADJUSTMENT_DECREASE' THEN -ebt.amount
                WHEN 'REVERSAL' THEN
                    CASE original.transaction_type
                        WHEN 'WITHHOLDING' THEN -ebt.amount
                        WHEN 'ADJUSTMENT_INCREASE' THEN -ebt.amount
                        WHEN 'REPAYMENT' THEN ebt.amount
                        WHEN 'ADJUSTMENT_DECREASE' THEN ebt.amount
                        ELSE 0
                    END
                ELSE 0
            END
        ),
        0
    )::NUMERIC(14, 2) AS balance
FROM users u
LEFT JOIN employee_balance_transactions ebt
       ON ebt.user_id = u.id
LEFT JOIN employee_balance_transactions original
       ON original.id = ebt.reversed_transaction_id
GROUP BY u.id;

COMMENT ON VIEW employee_balances IS
    'Текущий баланс удержаний: положительное значение — долг компании сотруднику';

CREATE OR REPLACE FUNCTION prevent_negative_employee_balance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$DECLARE
    current_balance NUMERIC(14, 2);
    delta NUMERIC(14, 2);
BEGIN
    -- Блокировка пользователя сериализует операции одного баланса.
    PERFORM 1
      FROM users
     WHERE id = NEW.user_id
     FOR UPDATE;

    SELECT balance
      INTO current_balance
      FROM employee_balances
     WHERE user_id = NEW.user_id;

    delta := CASE NEW.transaction_type
        WHEN 'WITHHOLDING' THEN NEW.amount
        WHEN 'ADJUSTMENT_INCREASE' THEN NEW.amount
        WHEN 'REPAYMENT' THEN -NEW.amount
        WHEN 'ADJUSTMENT_DECREASE' THEN -NEW.amount
        WHEN 'REVERSAL' THEN 0
        ELSE 0
    END;

    IF NEW.transaction_type = 'REVERSAL' THEN
        SELECT CASE original.transaction_type
            WHEN 'WITHHOLDING' THEN -NEW.amount
            WHEN 'ADJUSTMENT_INCREASE' THEN -NEW.amount
            WHEN 'REPAYMENT' THEN NEW.amount
            WHEN 'ADJUSTMENT_DECREASE' THEN NEW.amount
            ELSE 0
        END
        INTO delta
        FROM employee_balance_transactions original
        WHERE original.id = NEW.reversed_transaction_id
          AND original.user_id = NEW.user_id;

        IF delta IS NULL THEN
            RAISE EXCEPTION
                'Сторнируемая операция не найдена или относится к другому сотруднику'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF COALESCE(current_balance, 0) + delta < 0 THEN
        RAISE EXCEPTION
            'Операция превышает доступный баланс удержаний'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;$$;

DROP TRIGGER IF EXISTS trg_employee_balance_nonnegative
    ON employee_balance_transactions;
CREATE TRIGGER trg_employee_balance_nonnegative
BEFORE INSERT
ON employee_balance_transactions
FOR EACH ROW
EXECUTE FUNCTION prevent_negative_employee_balance();

CREATE OR REPLACE FUNCTION prevent_employee_balance_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$BEGIN
    RAISE EXCEPTION
        'Операции баланса неизменяемы; используйте сторнирование'
        USING ERRCODE = '55000';
END;$$;

DROP TRIGGER IF EXISTS trg_employee_balance_no_update
    ON employee_balance_transactions;
CREATE TRIGGER trg_employee_balance_no_update
BEFORE UPDATE
ON employee_balance_transactions
FOR EACH ROW
EXECUTE FUNCTION prevent_employee_balance_mutation();

DROP TRIGGER IF EXISTS trg_employee_balance_no_delete
    ON employee_balance_transactions;
CREATE TRIGGER trg_employee_balance_no_delete
BEFORE DELETE
ON employee_balance_transactions
FOR EACH ROW
EXECUTE FUNCTION prevent_employee_balance_mutation();

-- ============================================================
-- 9. Расходы директоров
-- ============================================================

ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS expense_scope VARCHAR(32)
        NOT NULL DEFAULT 'GENERAL';

ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS creator_role VARCHAR(20)
        NOT NULL DEFAULT '';

UPDATE expenses e
   SET creator_role = u.role
  FROM users u
 WHERE e.user_id = u.id
   AND btrim(e.creator_role) = '';

-- Старые расходы не переклассифицируются автоматически как директорские:
-- expense_scope фиксирует контекст создания, а не текущую роль пользователя.

DO $$BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_expenses_scope'
    ) THEN
        ALTER TABLE expenses
            ADD CONSTRAINT chk_expenses_scope
            CHECK (
                expense_scope IN ('GENERAL', 'DIRECTOR')
            ) NOT VALID;
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_expenses_scope_date
    ON expenses (expense_scope, date DESC);

CREATE INDEX IF NOT EXISTS idx_expenses_director_scope
    ON expenses (date DESC, user_id)
    WHERE expense_scope = 'DIRECTOR';

COMMENT ON COLUMN expenses.expense_scope IS
    'GENERAL либо DIRECTOR; значение фиксируется при создании расхода';

COMMENT ON COLUMN expenses.creator_role IS
    'Снимок роли автора на момент создания расхода';

-- ============================================================
-- 10. PRO-Chat: диалоги, сообщения и вложения
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_conversations (
    id UUID PRIMARY KEY,
    conversation_type VARCHAR(20) NOT NULL DEFAULT 'DIRECT',
    direct_key VARCHAR(80),
    created_by UUID NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    last_message_id UUID,
    CONSTRAINT chk_chat_conversation_type
        CHECK (
            conversation_type IN ('DIRECT')
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_conversations_direct_key
    ON chat_conversations (direct_key)
    WHERE direct_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_chat_conversations_updated
    ON chat_conversations (updated_at DESC);

CREATE TABLE IF NOT EXISTS chat_conversation_members (
    conversation_id UUID NOT NULL
        REFERENCES chat_conversations(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    user_id UUID NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    joined_at BIGINT NOT NULL,
    last_read_message_id UUID,
    last_read_at BIGINT,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_members_user_archived
    ON chat_conversation_members (user_id, is_archived, conversation_id);

CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL
        REFERENCES chat_conversations(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    sender_id UUID NOT NULL
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,
    body_ciphertext TEXT NOT NULL DEFAULT '',
    body_iv VARCHAR(64) NOT NULL DEFAULT '',
    body_key_version INTEGER NOT NULL DEFAULT 1,
    client_message_id VARCHAR(100) NOT NULL,
    reply_to_message_id UUID
        REFERENCES chat_messages(id)
        ON UPDATE CASCADE
        ON DELETE SET NULL,
    created_at BIGINT NOT NULL,
    edited_at BIGINT,
    deleted_at BIGINT,
    CONSTRAINT chk_chat_message_has_payload
        CHECK (
            btrim(body_ciphertext) <> ''
            OR deleted_at IS NOT NULL
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_messages_sender_client_id
    ON chat_messages (sender_id, client_message_id);

CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation_cursor
    ON chat_messages (conversation_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_chat_messages_reply
    ON chat_messages (reply_to_message_id)
    WHERE reply_to_message_id IS NOT NULL;

ALTER TABLE chat_conversations
    DROP CONSTRAINT IF EXISTS fk_chat_conversations_last_message;

ALTER TABLE chat_conversations
    ADD CONSTRAINT fk_chat_conversations_last_message
    FOREIGN KEY (last_message_id)
    REFERENCES chat_messages(id)
    ON UPDATE CASCADE
    ON DELETE SET NULL
    NOT VALID;

ALTER TABLE chat_conversation_members
    DROP CONSTRAINT IF EXISTS fk_chat_members_last_read_message;

ALTER TABLE chat_conversation_members
    ADD CONSTRAINT fk_chat_members_last_read_message
    FOREIGN KEY (last_read_message_id)
    REFERENCES chat_messages(id)
    ON UPDATE CASCADE
    ON DELETE SET NULL
    NOT VALID;

CREATE TABLE IF NOT EXISTS chat_attachments (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL
        REFERENCES chat_messages(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    original_name VARCHAR(500) NOT NULL,
    stored_name VARCHAR(500) NOT NULL,
    storage_path TEXT NOT NULL,
    mime_type VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream',
    size_bytes BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    encryption_iv VARCHAR(64) NOT NULL,
    encryption_key_version INTEGER NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    CONSTRAINT chk_chat_attachment_name_not_blank
        CHECK (
            btrim(original_name) <> ''
            AND btrim(stored_name) <> ''
        ),
    CONSTRAINT chk_chat_attachment_size
        CHECK (
            size_bytes >= 0
            AND size_bytes <= 104857600
        ),
    CONSTRAINT chk_chat_attachment_sha256
        CHECK (
            checksum_sha256 ~ '^[0-9a-fA-F]{64}$'
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_attachments_storage_path
    ON chat_attachments (storage_path);

CREATE INDEX IF NOT EXISTS idx_chat_attachments_message
    ON chat_attachments (message_id);

COMMENT ON COLUMN chat_messages.body_ciphertext IS
    'Шифротекст сообщения; TLS защищает транспорт, AES-GCM используется при хранении';

COMMENT ON COLUMN chat_attachments.size_bytes IS
    'Размер одного файла; максимум 100 MiB';

CREATE OR REPLACE FUNCTION validate_chat_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM chat_conversation_members ccm
        WHERE ccm.conversation_id = NEW.conversation_id
          AND ccm.user_id = NEW.sender_id
    ) THEN
        RAISE EXCEPTION
            'Отправитель не является участником диалога'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;$$;

DROP TRIGGER IF EXISTS trg_chat_message_membership
    ON chat_messages;
CREATE TRIGGER trg_chat_message_membership
BEFORE INSERT OR UPDATE OF conversation_id, sender_id
ON chat_messages
FOR EACH ROW
EXECUTE FUNCTION validate_chat_membership();

CREATE OR REPLACE FUNCTION touch_chat_conversation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$BEGIN
    UPDATE chat_conversations
       SET updated_at = NEW.created_at,
           last_message_id = NEW.id
     WHERE id = NEW.conversation_id;

    RETURN NEW;
END;$$;

DROP TRIGGER IF EXISTS trg_chat_message_touch_conversation
    ON chat_messages;
CREATE TRIGGER trg_chat_message_touch_conversation
AFTER INSERT
ON chat_messages
FOR EACH ROW
EXECUTE FUNCTION touch_chat_conversation();

-- ============================================================
-- 11. Индексы файлов чеков и билетов
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_expense_receipts_expense_uploaded
    ON expense_receipts (expense_id, uploaded_at DESC);

CREATE INDEX IF NOT EXISTS idx_ticket_receipts_ticket_uploaded
    ON ticket_receipts (ticket_id, uploaded_at DESC);

CREATE INDEX IF NOT EXISTS idx_tickets_uploaded_by_uploaded_at
    ON tickets (uploaded_by, uploaded_at DESC);

-- ============================================================
-- 12. Валидация добавленных ограничений
-- ============================================================

ALTER TABLE time_entries
    VALIDATE CONSTRAINT fk_time_entries_subproject;

ALTER TABLE expenses
    VALIDATE CONSTRAINT fk_expenses_subproject;

ALTER TABLE incomes
    VALIDATE CONSTRAINT fk_incomes_subproject;

ALTER TABLE business_trips
    VALIDATE CONSTRAINT fk_business_trips_subproject;

ALTER TABLE tickets
    VALIDATE CONSTRAINT fk_tickets_subproject;

ALTER TABLE salary_components
    VALIDATE CONSTRAINT fk_salary_components_subproject;

ALTER TABLE expenses
    VALIDATE CONSTRAINT chk_expenses_receipt_category;

ALTER TABLE incomes
    VALIDATE CONSTRAINT chk_incomes_receipt_category;

ALTER TABLE business_trips
    VALIDATE CONSTRAINT chk_business_trips_date_range;

ALTER TABLE business_trips
    VALIDATE CONSTRAINT chk_business_trips_status;

ALTER TABLE business_trips
    VALIDATE CONSTRAINT chk_business_trips_completed_end_date;

ALTER TABLE salary_records
    VALIDATE CONSTRAINT chk_salary_records_financial_amounts;

ALTER TABLE expenses
    VALIDATE CONSTRAINT chk_expenses_scope;

ALTER TABLE chat_conversations
    VALIDATE CONSTRAINT fk_chat_conversations_last_message;

ALTER TABLE chat_conversation_members
    VALIDATE CONSTRAINT fk_chat_members_last_read_message;

-- ============================================================
-- 13. Версия схемы
-- ============================================================

CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(255) PRIMARY KEY,
    applied_at BIGINT NOT NULL
);

INSERT INTO schema_migrations (version, applied_at)
VALUES (
    '2026-09-23-proles-platform-upgrade',
    (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT
)
ON CONFLICT (version) DO NOTHING;

COMMIT;