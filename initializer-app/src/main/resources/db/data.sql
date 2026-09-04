-- 首启初始化：预置两类草稿（版本 0、内容为空，发布前占位，可被管理端覆写）
-- WHERE NOT EXISTS 保证幂等：重复执行不产生重复行
INSERT INTO config_draft (kind, content_yaml, version, updated_by, updated_at)
SELECT 'METADATA', NULL, 0, 'system', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM config_draft WHERE kind = 'METADATA');

INSERT INTO config_draft (kind, content_yaml, version, updated_by, updated_at)
SELECT 'STARTER', NULL, 0, 'system', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM config_draft WHERE kind = 'STARTER');

INSERT INTO config_draft (kind, content_yaml, version, updated_by, updated_at)
SELECT 'STARTER_SYNC', NULL, 0, 'system', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM config_draft WHERE kind = 'STARTER_SYNC');
