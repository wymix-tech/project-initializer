-- project-initializer 管理端配置存储（M3.1/M3.2/M3.5 → 结构化改造 T1）
-- H2 兼容 DDL；生产替换数据源时需按目标方言调整
--
-- 版本作用域约定（关键）：全部 cfg_* 业务表均带 release_id：
--   release_id = 0 —— 当前草稿工作区（管理端表单编辑写这里）
--   release_id > 0  —— 某次发布快照（等于 config_release.id，只读不可变）
-- 因此**所有唯一约束都必须带 release_id**，否则发布复制行为新 release 时会与草稿行冲突，
-- 出现「发布改写草稿」的静默数据损坏。

-- ============ 版本与元信息 ============

-- 发布历史头表：只保留发布元信息，配置正文全部落在下面的 cfg_* 关系表（按 release_id 关联）
CREATE TABLE IF NOT EXISTS config_release (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    released_by VARCHAR(64),
    released_at TIMESTAMP,
    remark      VARCHAR(512)
);

-- 草稿种类级元信息：承载 kind 级乐观锁（替代原 config_draft.content_yaml）
-- 一 kind 一行；version 为乐观锁版本，结构表行本身不带版本列
CREATE TABLE IF NOT EXISTS config_draft_meta (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind       VARCHAR(32) NOT NULL,
    version    INT         NOT NULL DEFAULT 0,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP,
    CONSTRAINT uk_config_draft_meta_kind UNIQUE (kind)
);

-- 管理端审计日志（动作留痕，与版本作用域无关）
CREATE TABLE IF NOT EXISTS config_audit (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    action      VARCHAR(32) NOT NULL,
    target_kind VARCHAR(32),
    operator    VARCHAR(64),
    detail      VARCHAR(1024),
    created_at  TIMESTAMP
);

-- ============ 元数据（metadata.yml 同构） ============

-- Java 版本清单
CREATE TABLE IF NOT EXISTS cfg_java_version (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id               BIGINT      NOT NULL,
    version_id               VARCHAR(32) NOT NULL,
    label                    VARCHAR(128),
    deprecated               BOOLEAN     NOT NULL DEFAULT FALSE,
    recommended_boot_version VARCHAR(32),
    sort                     INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_java_version UNIQUE (release_id, version_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_java_version_release ON cfg_java_version (release_id);

-- Spring Boot 版本清单（含框架父 POM 三列，可空 = 回退官方父 POM）
CREATE TABLE IF NOT EXISTS cfg_boot_version (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id   BIGINT      NOT NULL,
    version_id   VARCHAR(32) NOT NULL,
    label        VARCHAR(128),
    official     BOOLEAN     NOT NULL DEFAULT TRUE,
    fp_group     VARCHAR(256),
    fp_artifact  VARCHAR(256),
    fp_version   VARCHAR(128),
    sort         INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_boot_version UNIQUE (release_id, version_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_boot_version_release ON cfg_boot_version (release_id);

-- Boot ↔ Java 兼容矩阵（多对多）。
-- 注意：这里存的是业务 ID（version_id 字符串）而非父行自增主键 ——
-- 草稿态必须能把「引用了尚未登记/拼错的 Java 版本」原样存下来，
-- 才能让发布期给出「Boot 版本 X 引用了未定义的 Java 版本 Y」的 4002 明细；
-- 若强绑主键，非法引用在保存阶段就会被外键/非空约束挡掉或被迫丢弃，
-- 用户永远看不到问题所在。boot_pk 仍用主键（父行必然存在，引用对象是 Boot 行本身）；
-- java_version_id 与 cfg_java_version.version_id 在装配期按作用域解析。
CREATE TABLE IF NOT EXISTS cfg_boot_java (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id      BIGINT      NOT NULL,
    boot_pk         BIGINT      NOT NULL,
    java_version_id VARCHAR(32) NOT NULL,
    CONSTRAINT uk_cfg_boot_java UNIQUE (release_id, boot_pk, java_version_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_boot_java_release ON cfg_boot_java (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_boot_java_boot ON cfg_boot_java (release_id, boot_pk);

-- Boot 版本可选 starter 白名单（显式声明；未声明即对全部清单条目可选）
CREATE TABLE IF NOT EXISTS cfg_boot_starter_ref (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT       NOT NULL,
    boot_pk    BIGINT       NOT NULL,
    starter_id VARCHAR(128) NOT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_boot_starter_ref UNIQUE (release_id, boot_pk, starter_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_boot_starter_ref_release ON cfg_boot_starter_ref (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_boot_starter_ref_boot ON cfg_boot_starter_ref (release_id, boot_pk);

-- ============ starter 清单（catalog/starters/*.yml 同构） ============

-- starter 主表：source 区分手工（MANUAL）与制品库同步（SYNC）通道，同名手工优先
CREATE TABLE IF NOT EXISTS cfg_starter (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id     BIGINT       NOT NULL,
    starter_id     VARCHAR(128) NOT NULL,
    category       VARCHAR(32)  NOT NULL,
    maintainer     VARCHAR(32)  NOT NULL,
    description    VARCHAR(512),
    required       BOOLEAN      NOT NULL DEFAULT FALSE,
    hidden         BOOLEAN      NOT NULL DEFAULT FALSE,
    config_package VARCHAR(512),
    source         VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    sort           INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_starter UNIQUE (release_id, starter_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_release ON cfg_starter (release_id);

-- starter 依赖坐标（GAV，version 可空 = 走 Boot BOM）
CREATE TABLE IF NOT EXISTS cfg_starter_dependency (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id  BIGINT       NOT NULL,
    starter_pk  BIGINT       NOT NULL,
    group_id    VARCHAR(256) NOT NULL,
    artifact_id VARCHAR(256) NOT NULL,
    version     VARCHAR(128),
    sort        INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_starter_dependency UNIQUE (release_id, starter_pk, group_id, artifact_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_dependency_release ON cfg_starter_dependency (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_dependency_starter ON cfg_starter_dependency (release_id, starter_pk);

-- 依赖联动 OR 分组（AND-of-OR 第一层：组之间为 AND）
CREATE TABLE IF NOT EXISTS cfg_starter_require_group (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id  BIGINT NOT NULL,
    starter_pk  BIGINT NOT NULL,
    group_index INT    NOT NULL,
    CONSTRAINT uk_cfg_starter_require_group UNIQUE (release_id, starter_pk, group_index)
);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_require_group_release ON cfg_starter_require_group (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_require_group_starter ON cfg_starter_require_group (release_id, starter_pk);

-- 依赖联动 OR 组内项（AND-of-OR 第二层：组内任一被选即满足本组）
CREATE TABLE IF NOT EXISTS cfg_starter_require_item (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id          BIGINT       NOT NULL,
    group_pk            BIGINT       NOT NULL,
    required_starter_id VARCHAR(128) NOT NULL,
    sort                INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_starter_require_item UNIQUE (release_id, group_pk, required_starter_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_require_item_release ON cfg_starter_require_item (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_require_item_group ON cfg_starter_require_item (release_id, group_pk);

-- starter ↔ Boot 版本关联（空 = 全部 Boot 版本可用）
CREATE TABLE IF NOT EXISTS cfg_starter_boot_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id      BIGINT      NOT NULL,
    starter_pk      BIGINT      NOT NULL,
    boot_version_id VARCHAR(32) NOT NULL,
    CONSTRAINT uk_cfg_starter_boot_version UNIQUE (release_id, starter_pk, boot_version_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_boot_version_release ON cfg_starter_boot_version (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_boot_version_starter ON cfg_starter_boot_version (release_id, starter_pk);

-- 模板联动项（linkages.templates）
CREATE TABLE IF NOT EXISTS cfg_starter_linkage_template (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT       NOT NULL,
    starter_pk BIGINT       NOT NULL,
    source     VARCHAR(512) NOT NULL,
    target     VARCHAR(512) NOT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_starter_linkage_template UNIQUE (release_id, starter_pk, target)
);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_linkage_template_release ON cfg_starter_linkage_template (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_linkage_template_starter ON cfg_starter_linkage_template (release_id, starter_pk);

-- application.yml 片段联动项（linkages.application-yml-fragments）
CREATE TABLE IF NOT EXISTS cfg_starter_linkage_yml (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id     BIGINT       NOT NULL,
    starter_pk     BIGINT       NOT NULL,
    source         VARCHAR(512) NOT NULL,
    merge_strategy VARCHAR(16)  NOT NULL DEFAULT 'APPEND',
    sort           INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_starter_linkage_yml UNIQUE (release_id, starter_pk, source)
);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_linkage_yml_release ON cfg_starter_linkage_yml (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_linkage_yml_starter ON cfg_starter_linkage_yml (release_id, starter_pk);

-- 集成抽象类联动项（linkages.abstract-classes）
CREATE TABLE IF NOT EXISTS cfg_starter_linkage_abstract (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT       NOT NULL,
    starter_pk BIGINT       NOT NULL,
    fqcn       VARCHAR(512) NOT NULL,
    alias      VARCHAR(128) NOT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_starter_linkage_abstract UNIQUE (release_id, starter_pk, alias)
);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_linkage_abstract_release ON cfg_starter_linkage_abstract (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_starter_linkage_abstract_starter ON cfg_starter_linkage_abstract (release_id, starter_pk);

-- ============ 冲突规则（conflict-rules.yml 同构） ============

-- 冲突规则主表（type = PAIR / GROUP）
CREATE TABLE IF NOT EXISTS cfg_conflict_rule (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT       NOT NULL,
    rule_id    VARCHAR(128) NOT NULL,
    rule_type  VARCHAR(16)  NOT NULL,
    message    VARCHAR(1024) NOT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_conflict_rule UNIQUE (release_id, rule_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_conflict_rule_release ON cfg_conflict_rule (release_id);

-- 冲突规则涉及的 starter（rule ↔ starter 多选，独立子表）
CREATE TABLE IF NOT EXISTS cfg_conflict_rule_starter (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT       NOT NULL,
    rule_pk    BIGINT       NOT NULL,
    starter_id VARCHAR(128) NOT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_cfg_conflict_rule_starter UNIQUE (release_id, rule_pk, starter_id)
);
CREATE INDEX IF NOT EXISTS idx_cfg_conflict_rule_starter_release ON cfg_conflict_rule_starter (release_id);
CREATE INDEX IF NOT EXISTS idx_cfg_conflict_rule_starter_rule ON cfg_conflict_rule_starter (release_id, rule_pk);

-- ============ 生成模板（templates/**） ============

-- 模板名 + 模板全文（CLOB）；列表页只查 name/updated_at，content 按需单独取
CREATE TABLE IF NOT EXISTS cfg_template (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT       NOT NULL,
    name       VARCHAR(512) NOT NULL,
    content    CLOB,
    sort       INT          NOT NULL DEFAULT 0,
    updated_at TIMESTAMP,
    CONSTRAINT uk_cfg_template UNIQUE (release_id, name)
);
CREATE INDEX IF NOT EXISTS idx_cfg_template_release ON cfg_template (release_id);

-- ============ 增量升级（幂等） ============
-- schema.sql 以 CREATE TABLE IF NOT EXISTS 建表，已存在的库不会因改列而重建，
-- 因此新增列必须在此处补幂等 ALTER，保证旧库重启后自动补齐（可重复执行）。

-- T1 补丁：starter 主表补 hidden 列（对应 StarterCatalogEntry.hidden，前端依赖选择列表默认隐藏）
ALTER TABLE cfg_starter ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;

-- T4 补丁：starter 唯一约束口径修正
-- 原 uk_cfg_starter(release_id, starter_id) 未包含 source，与「手工 MANUAL / 同步 SYNC 两通道并存」
-- 的语义冲突：同一 release 作用域内，两个通道各有一条同名 starter 是合法状态
-- （「同名手工优先」由发布期 CatalogMerger 的合并保留集保证，而非靠唯一约束互斥）。
-- 改为 (release_id, source, starter_id) 后，两通道可各自持有同名条目，且各通道内部仍禁止重名。
ALTER TABLE cfg_starter DROP CONSTRAINT IF EXISTS uk_cfg_starter;
ALTER TABLE cfg_starter ADD CONSTRAINT IF NOT EXISTS uk_cfg_starter_source
    UNIQUE (release_id, source, starter_id);
