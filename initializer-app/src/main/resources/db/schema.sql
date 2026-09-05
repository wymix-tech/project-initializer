-- project-initializer 管理端配置存储（M3.1/M3.2）
-- H2 兼容 DDL；生产替换数据源时需按目标方言调整

CREATE TABLE IF NOT EXISTS config_draft (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind         VARCHAR(32)  NOT NULL,
    content_yaml CLOB,
    version      INT          NOT NULL DEFAULT 0,
    updated_by   VARCHAR(64),
    updated_at   TIMESTAMP,
    CONSTRAINT uk_config_draft_kind UNIQUE (kind)
);

CREATE TABLE IF NOT EXISTS config_release (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    metadata_yaml CLOB,
    starter_yaml  CLOB,
    template_yaml CLOB,
    conflict_yaml CLOB,
    released_by   VARCHAR(64),
    released_at   TIMESTAMP,
    remark        VARCHAR(512)
);

CREATE TABLE IF NOT EXISTS config_audit (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    action     VARCHAR(32) NOT NULL,
    target_kind VARCHAR(32),
    operator   VARCHAR(64),
    detail     VARCHAR(1024),
    created_at TIMESTAMP
);
