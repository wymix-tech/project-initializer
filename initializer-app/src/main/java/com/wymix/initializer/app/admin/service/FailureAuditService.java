package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Failure evidence survives rollback of the attempted configuration change. */
@Service
public class FailureAuditService {
    private final ConfigRepository repository;

    public FailureAuditService(ConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 独立事务写入失败记录，不随外层配置事务回滚。
     * @param action 审计动作
     * @param kind 配置类型，可空
     * @param operator 操作人
     * @param detail 失败明细
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, ConfigKind kind, String operator, String detail) {
        repository.insertAudit(action, kind, operator, detail);
    }
}
