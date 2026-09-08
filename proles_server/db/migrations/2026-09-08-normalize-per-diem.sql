-- Normalize legacy per-diem expense types without deleting or changing any expense data.
UPDATE expenses
SET type = 'PER_DIEM'
WHERE LOWER(type) IN ('per_diem', 'perdiem');
