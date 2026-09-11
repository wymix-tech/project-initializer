-- 首启初始化：预置草稿种类元信息（版本 0、无修改人，发布前占位，可被管理端覆写）
-- kind 清单与 ConfigKind 枚举对齐：metadata / starter / conflict-rule / template / starter-sync
-- 配置正文不再预置（结构化 cfg_* 表空即代表空草稿）；
-- WHERE NOT EXISTS 保证幂等：重复执行不产生重复行
INSERT INTO config_draft_meta (kind, version, updated_by, updated_at)
SELECT 'METADATA', 0, 'system', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM config_draft_meta WHERE kind = 'METADATA');

INSERT INTO config_draft_meta (kind, version, updated_by, updated_at)
SELECT 'STARTER', 0, 'system', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM config_draft_meta WHERE kind = 'STARTER');

INSERT INTO config_draft_meta (kind, version, updated_by, updated_at)
SELECT 'STARTER_SYNC', 0, 'system', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM config_draft_meta WHERE kind = 'STARTER_SYNC');

INSERT INTO config_draft_meta (kind, version, updated_by, updated_at)
SELECT 'TEMPLATE', 0, 'system', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM config_draft_meta WHERE kind = 'TEMPLATE');

INSERT INTO config_draft_meta (kind, version, updated_by, updated_at)
SELECT 'CONFLICT_RULE', 0, 'system', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM config_draft_meta WHERE kind = 'CONFLICT_RULE');
