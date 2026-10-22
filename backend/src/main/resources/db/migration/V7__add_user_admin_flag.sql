ALTER TABLE app_user
    ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE AFTER status;

-- 引导：把系统里最早的那个账号提升为管理员，避免升级后无人能进后台。
-- 仅在「当前一个管理员都没有」时才执行，重复迁移或已有管理员时保持不动。
UPDATE app_user
SET is_admin = TRUE
WHERE id = (SELECT min_id FROM (SELECT MIN(id) AS min_id FROM app_user) AS bootstrap)
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM app_user WHERE is_admin = TRUE) AS existing_admin);
