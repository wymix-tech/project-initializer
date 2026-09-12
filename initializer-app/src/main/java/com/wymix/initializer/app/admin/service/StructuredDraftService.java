package com.wymix.initializer.app.admin.service;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.dto.CatalogDtos.AbstractClassDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.BootVersionDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.ConflictRuleDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.ConflictRuleUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.ConflictRuleView;
import com.wymix.initializer.app.admin.dto.CatalogDtos.DependencyDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.DraftSaveResult;
import com.wymix.initializer.app.admin.dto.CatalogDtos.FrameworkParentDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.JavaVersionDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.LinkageDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.MetadataUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.MetadataView;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterCatalogUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterCatalogView;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.StarterUpsertDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateCatalogUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateDetail;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateListView;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateSummary;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateUpsertRequest;
import com.wymix.initializer.app.admin.dto.CatalogDtos.TemplateLinkageDto;
import com.wymix.initializer.app.admin.dto.CatalogDtos.YmlFragmentDto;
import com.wymix.initializer.app.admin.entity.CfgBootJavaEntity;
import com.wymix.initializer.app.admin.entity.CfgBootStarterRefEntity;
import com.wymix.initializer.app.admin.entity.CfgBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleEntity;
import com.wymix.initializer.app.admin.entity.CfgConflictRuleStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgJavaVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterDependencyEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageAbstractEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageTemplateEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterLinkageYmlEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireGroupEntity;
import com.wymix.initializer.app.admin.entity.CfgStarterRequireItemEntity;
import com.wymix.initializer.app.admin.entity.CfgTemplateEntity;
import com.wymix.initializer.app.admin.entity.ConfigDraftMetaEntity;
import com.wymix.initializer.app.admin.entity.ConfigKind;
import com.wymix.initializer.app.admin.repository.ConfigRepository;
import com.wymix.initializer.app.admin.repository.StructuredSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 结构化草稿应用服务（结构化改造 T3）：四类配置模块（元数据 / starter 清单 / 冲突规则 / 模板）
 * 的表单化读写——**直接操作 {@code cfg_*} 草稿行（{@code release_id = 0}），不再拼 YAML**。
 *
 * <p><b>与 {@link ConfigService} 的分工</b>：
 * <ul>
 *   <li>本类负责「结构化 DTO ↔ 结构行」的读写转换与 kind 级乐观锁推进</li>
 *   <li>{@link ConfigService} 继续负责发布 / 回滚 / 发布历史 / 审计查询</li>
 *   <li>{@code ConfigService} 的 YAML 草稿契约（T1 过渡桥，经 {@code StructuredYamlCodec} /
 *       {@code StructuredDraftWriter}）**保留**，供导入通道（T4）与旧客户端使用</li>
 * </ul>
 *
 * <p><b>权威来源</b>：结构化端点与 YAML 端点读写的都是同一批 {@code release_id = 0} 结构行，
 * 因此两条契约天然同源、可混用（结构化写入 + YAML 读回 = 同一份数据）。
 * 唯一需要显式同步的是 {@code config_draft_meta.version}（kind 级乐观锁），
 * 本类写入时与 {@link ConfigService} 走同一套推进规则，保证 {@code If-Match} 语义连贯。
 *
 * <p><b>草稿宽松 / 发布严格</b>：本类只做结构级与字段级校验（必填、取值域、数量上限），
 * 不做跨模块引用与图合法性校验——悬空 Java 引用、重复 ID、requires 成环等
 * 统一由发布期的 {@code StructuredCatalogAssembler} 收敛为 4002 明细，
 * 保证用户能「边补边存」。
 *
 * <p><b>禁 N+1</b>：读取一律经 {@link ConfigRepository#readDraftScope(ConfigKind)}（按 kind 切分）
 * 或 {@link ConfigRepository#readScope(long)}（一次批量），写入一律「先清后整体写」，
 * 全程无逐行查询。
 *
 * <p>线程安全性：无状态单例（全部状态在入参与返回值中），线程安全。
 */
@Service
public class StructuredDraftService {

    private static final Logger log = LoggerFactory.getLogger(StructuredDraftService.class);

    /** 审计动作：结构化草稿更新。 */
    public static final String ACTION_UPDATE_STRUCTURED = "UPDATE_STRUCTURED";

    /** 单模块条目数量上限（防误提交超大请求打爆内存与库）。 */
    public static final int MAX_ITEMS = 512;
    /** 单模块结构行数量上限（含子行，如依赖/联动/分组项）。 */
    public static final int MAX_ROWS = 20000;
    /** 单条模板 content 字符数上限（与 schema 的 CLOB 使用习惯对齐，防误粘贴超大文件）。 */
    public static final int MAX_TEMPLATE_LENGTH = 512 * 1024;

    private final ConfigRepository repository;

    /**
     * @param repository 配置存储仓库（结构化 cfg_* 表读写）
     */
    public StructuredDraftService(ConfigRepository repository) {
        this.repository = repository;
    }

    // ==================== 元数据 ====================

    /**
     * 读取元数据模块草稿（Java 版本 + Boot 版本 + 兼容矩阵 + starter 白名单）。
     *
     * @return 元数据视图（无结构行时两个清单均为空）
     */
    public MetadataView getMetadata() {
        StructuredSnapshot rows = repository.readDraftScope(ConfigKind.METADATA);
        Map<Long, List<String>> javaRefs = new LinkedHashMap<>();
        for (CfgBootJavaEntity row : rows.bootJava()) {
            javaRefs.computeIfAbsent(row.getBootPk(), k -> new ArrayList<>()).add(row.getJavaVersionId());
        }
        Map<Long, List<String>> starterRefs = groupStrings(rows.bootStarterRefs(),
                CfgBootStarterRefEntity::getBootPk, CfgBootStarterRefEntity::getStarterId);
        List<JavaVersionDto> javaVersions = rows.javaVersions().stream()
                .sorted(Comparator.comparingInt(r -> sortKey(r.getSort())))
                .map(r -> new JavaVersionDto(r.getVersionId(), r.getLabel(),
                        Boolean.TRUE.equals(r.getDeprecated()), r.getRecommendedBootVersion(), r.getSort()))
                .toList();
        List<BootVersionDto> bootVersions = rows.bootVersions().stream()
                .sorted(Comparator.comparingInt(r -> sortKey(r.getSort())))
                .map(r -> new BootVersionDto(r.getVersionId(), r.getLabel(),
                        !Boolean.FALSE.equals(r.getOfficial()),
                        javaRefs.getOrDefault(r.getId(), List.of()),
                        starterRefs.getOrDefault(r.getId(), List.of()),
                        frameworkParentOf(r), r.getSort()))
                .toList();
        return new MetadataView(javaVersions, bootVersions);
    }

    /**
     * 整体保存元数据模块草稿（先清空该 kind 草稿行，再按提交顺序写入）。
     *
     * @param request 元数据整体提交请求
     * @param ifMatch 乐观锁期望版本（GET 草稿返回的 version；首次保存传 null）
     * @return 保存结果（写入行数 + 新版本 + 规模摘要）
     * @throws BusinessException 结构非法（4002）或乐观锁冲突（4003）
     */
    @Transactional
    public DraftSaveResult saveMetadata(MetadataUpsertRequest request, Integer ifMatch) {
        requireRequest(request, "metadata");
        requireOperator(request.operator());
        List<JavaVersionDto> javaDtos = nullSafe(request.javaVersions());
        List<BootVersionDto> bootDtos = nullSafe(request.bootVersions());
        requireWithin("javaVersions", javaDtos.size());
        requireWithin("bootVersions", bootDtos.size());

        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        int sort = 0;
        long placeholderPk = 0L;
        Map<String, CfgBootVersionEntity> bootByBusiId = new LinkedHashMap<>();
        Set<String> javaIds = new LinkedHashSet<>();
        for (JavaVersionDto dto : javaDtos) {
            String id = requireText(dto.id(), "javaVersions[].id");
            if (!javaIds.add(id)) {
                throw invalid("元数据 Java 版本 ID 重复: " + id);
            }
            CfgJavaVersionEntity row = new CfgJavaVersionEntity();
            row.setVersionId(id);
            row.setLabel(dto.label() == null || dto.label().isBlank() ? "Java " + id : dto.label());
            row.setDeprecated(Boolean.TRUE.equals(dto.deprecated()));
            row.setRecommendedBootVersion(blankToNull(dto.recommendedBootVersion()));
            row.setSort(dto.sort() == null ? sort : dto.sort());
            sort++;
            snapshot.javaVersions().add(row);
        }
        sort = 0;
        Set<String> bootIds = new LinkedHashSet<>();
        for (BootVersionDto dto : bootDtos) {
            String id = requireText(dto.id(), "bootVersions[].id");
            if (!bootIds.add(id)) {
                throw invalid("元数据 Boot 版本 ID 重复: " + id);
            }
            CfgBootVersionEntity row = new CfgBootVersionEntity();
            // 显式占位主键（负数区间）：子行父键据此定位父行，由仓库层写入期换算
            row.setId(--placeholderPk);
            row.setVersionId(id);
            row.setLabel(dto.label() == null || dto.label().isBlank() ? "Spring Boot " + id : dto.label());
            row.setOfficial(!Boolean.FALSE.equals(dto.official()));
            applyFrameworkParent(row, dto.frameworkParent());
            row.setSort(dto.sort() == null ? sort : dto.sort());
            sort++;
            snapshot.bootVersions().add(row);
            bootByBusiId.put(id, row);
        }
        for (BootVersionDto dto : bootDtos) {
            CfgBootVersionEntity bootRow = bootByBusiId.get(dto.id());
            for (String javaId : nullSafe(dto.supportedJavaVersions())) {
                // 兼容矩阵存业务 ID：悬空引用原样落库（草稿宽松），发布期给出精确明细
                CfgBootJavaEntity link = new CfgBootJavaEntity();
                link.setBootPk(bootRow.getId());
                link.setJavaVersionId(requireText(javaId, "bootVersions[].supportedJavaVersions[]"));
                snapshot.bootJava().add(link);
            }
            int refSort = 0;
            Set<String> refs = new LinkedHashSet<>();
            for (String starterId : nullSafe(dto.starters())) {
                String text = requireText(starterId, "bootVersions[].starters[]");
                if (refs.add(text)) {
                    CfgBootStarterRefEntity ref = new CfgBootStarterRefEntity();
                    ref.setBootPk(bootRow.getId());
                    ref.setStarterId(text);
                    ref.setSort(refSort++);
                    snapshot.bootStarterRefs().add(ref);
                }
            }
        }
        requireWithinRows(snapshot.rowCount());
        String summary = "Java 版本 " + snapshot.javaVersions().size()
                + " 个 / Boot 版本 " + snapshot.bootVersions().size()
                + " 个 / 兼容矩阵 " + snapshot.bootJava().size()
                + " 条 / starter 白名单 " + snapshot.bootStarterRefs().size() + " 条";
        return write(ConfigKind.METADATA, snapshot, ifMatch, request.operator(), summary);
    }

    // ==================== starter 清单 ====================

    /**
     * 读取手工 starter 清单草稿。
     *
     * @return starter 清单视图（无结构行时为空清单）
     */
    public StarterCatalogView getStarters() {
        return new StarterCatalogView(readStarters(ConfigKind.STARTER));
    }

    /**
     * 整体保存手工 starter 清单草稿（仅 {@code source = MANUAL} 行，不触碰同步层）。
     *
     * @param request starter 清单整体提交请求
     * @param ifMatch 乐观锁期望版本
     * @return 保存结果
     * @throws BusinessException 结构非法（4002）或乐观锁冲突（4003）
     */
    @Transactional
    public DraftSaveResult saveStarters(StarterCatalogUpsertRequest request, Integer ifMatch) {
        return saveStarters(request, ifMatch, ConfigKind.STARTER);
    }

    /**
     * 保存指定来源通道的 starter 草稿（T3 内部入口，供制品库同步链路复用）。
     *
     * <p>{@code kind = STARTER} 写手工通道（{@code source = MANUAL}），
     * {@code kind = STARTER_SYNC} 写制品库同步通道（{@code source = SYNC}）；
     * 两者互不触碰对方行，「同名手工优先」由上游合并保留集保证。
     *
     * @param request 整体提交请求
     * @param ifMatch 乐观锁期望版本（同步链路传 null = 按当前版本推进）
     * @param kind    {@code STARTER}（手工）或 {@code STARTER_SYNC}（同步层）
     * @return 保存结果
     * @throws BusinessException 结构非法（4002）或乐观锁冲突（4003）
     */
    @Transactional
    public DraftSaveResult saveStarters(StarterCatalogUpsertRequest request, Integer ifMatch, ConfigKind kind) {
        if (kind != ConfigKind.STARTER && kind != ConfigKind.STARTER_SYNC) {
            throw invalid("starter 草稿写入通道非法: " + kind);
        }
        requireRequest(request, "starters");
        requireOperator(request.operator());
        List<StarterUpsertDto> dtos = nullSafe(request.starters());
        requireWithin("starters", dtos.size());
        String source = kind == ConfigKind.STARTER
                ? ConfigRepository.SOURCE_MANUAL : ConfigRepository.SOURCE_SYNC;
        StructuredSnapshot snapshot = buildStarters(dtos, source);
        requireWithinRows(snapshot.rowCount());
        int deps = 0;
        for (StarterDto dto : readStarters(snapshot)) {
            deps += dto.dependencies().size();
        }
        String summary = "starter " + snapshot.starters().size() + " 项 / 依赖 " + deps
                + " 条 / 联动 " + (snapshot.starterLinkageTemplates().size()
                + snapshot.starterLinkageYmls().size() + snapshot.starterLinkageAbstracts().size()) + " 条";
        return write(kind, snapshot, ifMatch, request.operator(), summary);
    }

    // ==================== 冲突规则 ====================

    /**
     * 读取冲突规则草稿。
     *
     * @return 规则视图（无结构行时为空清单）
     */
    public ConflictRuleView getConflictRules() {
        StructuredSnapshot rows = repository.readDraftScope(ConfigKind.CONFLICT_RULE);
        Map<Long, List<String>> members = groupStrings(rows.conflictRuleStarters(),
                CfgConflictRuleStarterEntity::getRulePk, CfgConflictRuleStarterEntity::getStarterId);
        List<ConflictRuleDto> rules = rows.conflictRules().stream()
                .sorted(Comparator.comparingInt(r -> sortKey(r.getSort())))
                .map(r -> new ConflictRuleDto(r.getRuleId(),
                        r.getRuleType() == null ? null : r.getRuleType().toLowerCase(Locale.ROOT),
                        members.getOrDefault(r.getId(), List.of()), r.getMessage(), r.getSort()))
                .toList();
        return new ConflictRuleView(rules);
    }

    /**
     * 整体保存冲突规则草稿。
     *
     * @param request 冲突规则整体提交请求
     * @param ifMatch 乐观锁期望版本
     * @return 保存结果
     * @throws BusinessException 结构非法（4002）或乐观锁冲突（4003）
     */
    @Transactional
    public DraftSaveResult saveConflictRules(ConflictRuleUpsertRequest request, Integer ifMatch) {
        requireRequest(request, "conflict-rules");
        requireOperator(request.operator());
        List<ConflictRuleDto> dtos = nullSafe(request.rules());
        requireWithin("rules", dtos.size());
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        long placeholderPk = 0L;
        int sort = 0;
        Set<String> ruleIds = new LinkedHashSet<>();
        for (ConflictRuleDto dto : dtos) {
            String id = requireText(dto.id(), "rules[].id");
            if (!ruleIds.add(id)) {
                throw invalid("冲突规则 ID 重复: " + id);
            }
            String type = requireText(dto.type(), "rules[].type").toLowerCase(Locale.ROOT);
            if (!"pair".equals(type) && !"group".equals(type)) {
                throw invalid("冲突规则类型非法（仅允许 pair / group）: " + dto.type());
            }
            CfgConflictRuleEntity row = new CfgConflictRuleEntity();
            row.setId(--placeholderPk);
            row.setRuleId(id);
            row.setRuleType(type);
            row.setMessage(dto.message());
            row.setSort(dto.sort() == null ? sort : dto.sort());
            sort++;
            snapshot.conflictRules().add(row);
        }
        int members = 0;
        for (ConflictRuleDto dto : dtos) {
            CfgConflictRuleEntity ruleRow = snapshot.conflictRules().stream()
                    .filter(r -> r.getRuleId().equals(dto.id()))
                    .findFirst().orElseThrow();
            int memberSort = 0;
            for (String starterId : nullSafe(dto.starters())) {
                CfgConflictRuleStarterEntity row = new CfgConflictRuleStarterEntity();
                row.setRulePk(ruleRow.getId());
                row.setStarterId(requireText(starterId, "rules[].starters[]"));
                row.setSort(memberSort++);
                snapshot.conflictRuleStarters().add(row);
                members++;
            }
        }
        requireWithinRows(snapshot.rowCount());
        String summary = "规则 " + snapshot.conflictRules().size() + " 条 / 涉及 starter " + members + " 项";
        return write(ConfigKind.CONFLICT_RULE, snapshot, ifMatch, request.operator(), summary);
    }

    // ==================== 模板 ====================

    /**
     * 读取模板列表（**只查 name / 更新时间 / 顺序，不搬 content CLOB**）。
     *
     * @return 模板摘要清单（按 sort 升序）
     */
    public TemplateListView listTemplates() {
        List<TemplateSummary> list = repository.listDraftTemplateSummaries().stream()
                .sorted(Comparator.comparingInt(r -> sortKey(r.getSort())))
                .map(r -> new TemplateSummary(r.getName(), r.getUpdatedAt(), r.getSort()))
                .toList();
        return new TemplateListView(list);
    }

    /**
     * 读取单个模板详情（含 content 全文，按需单独取）。
     *
     * @param name 模板名
     * @return 模板详情
     * @throws BusinessException 模板不存在（4001 语义，草稿未配置）
     */
    public TemplateDetail getTemplate(String name) {
        String key = requireText(name, "name");
        CfgTemplateEntity row = repository.findDraftTemplate(key);
        if (row == null) {
            throw new BusinessException(ErrorCode.CONFIG_DRAFT_NOT_FOUND,
                    "模板草稿不存在: " + key);
        }
        return new TemplateDetail(row.getName(), row.getContent(), row.getUpdatedAt(), row.getSort());
    }

    /**
     * 新增或更新单个模板（按 name 幂等 upsert，保留其余模板不动）。
     *
     * @param request 单模板请求
     * @param ifMatch 乐观锁期望版本
     * @return 保存结果
     * @throws BusinessException 结构非法（4002）或乐观锁冲突（4003）
     */
    @Transactional
    public DraftSaveResult saveTemplate(TemplateUpsertRequest request, Integer ifMatch) {
        requireRequest(request, "template");
        requireOperator(request.operator());
        String name = requireText(request.name(), "name");
        String content = requireText(request.content(), "content");
        requireTemplateLength(name, content);
        List<TemplateDetail> existing = repository.listDraftTemplates().stream()
                .map(r -> new TemplateDetail(r.getName(), r.getContent(), r.getUpdatedAt(), r.getSort()))
                .toList();
        List<TemplateDetail> merged = new ArrayList<>();
        boolean replaced = false;
        for (TemplateDetail item : existing) {
            if (item.name().equals(name)) {
                merged.add(new TemplateDetail(name, content, Instant.now(),
                        request.sort() == null ? item.sort() : request.sort()));
                replaced = true;
            } else {
                merged.add(item);
            }
        }
        if (!replaced) {
            int nextSort = merged.stream().mapToInt(t -> sortKey(t.sort())).max().orElse(-1) + 1;
            merged.add(new TemplateDetail(name, content, Instant.now(),
                    request.sort() == null ? nextSort : request.sort()));
        }
        return writeTemplates(merged, ifMatch, request.operator());
    }

    /**
     * 整体保存模板集草稿（全量替换）。
     *
     * @param request 模板集合整体提交请求
     * @param ifMatch 乐观锁期望版本
     * @return 保存结果
     * @throws BusinessException 结构非法（4002）或乐观锁冲突（4003）
     */
    @Transactional
    public DraftSaveResult saveTemplates(TemplateCatalogUpsertRequest request, Integer ifMatch) {
        requireRequest(request, "templates");
        requireOperator(request.operator());
        List<TemplateDetail> dtos = nullSafe(request.templates());
        requireWithin("templates", dtos.size());
        return writeTemplates(dtos, ifMatch, request.operator());
    }

    /**
     * 删除单个模板（按 name；不影响其余模板）。
     *
     * @param name     模板名
     * @param operator 操作人
     * @param ifMatch  乐观锁期望版本
     * @return 保存结果
     * @throws BusinessException 模板不存在（4001）或乐观锁冲突（4003）
     */
    @Transactional
    public DraftSaveResult deleteTemplate(String name, String operator, Integer ifMatch) {
        requireOperator(operator);
        String key = requireText(name, "name");
        List<TemplateDetail> existing = repository.listDraftTemplates().stream()
                .map(r -> new TemplateDetail(r.getName(), r.getContent(), r.getUpdatedAt(), r.getSort()))
                .toList();
        List<TemplateDetail> remaining = existing.stream()
                .filter(t -> !t.name().equals(key))
                .toList();
        if (remaining.size() == existing.size()) {
            throw new BusinessException(ErrorCode.CONFIG_DRAFT_NOT_FOUND, "模板草稿不存在: " + key);
        }
        return writeTemplates(remaining, ifMatch, operator);
    }

    // ==================== 内部：写入 ====================

    /**
     * 写模板集（整体替换草稿模板行）。
     *
     * @param templates 模板项（全量）
     * @param ifMatch   乐观锁期望版本
     * @param operator  操作人
     * @return 保存结果
     */
    private DraftSaveResult writeTemplates(List<TemplateDetail> templates, Integer ifMatch, String operator) {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        int sort = 0;
        Set<String> names = new LinkedHashSet<>();
        Instant now = Instant.now();
        for (TemplateDetail dto : templates) {
            String name = requireText(dto.name(), "templates[].name");
            String content = requireText(dto.content(), "templates[].content");
            requireTemplateLength(name, content);
            if (!names.add(name)) {
                throw invalid("模板名称重复: " + name);
            }
            CfgTemplateEntity row = new CfgTemplateEntity();
            row.setName(name);
            row.setContent(content);
            row.setUpdatedAt(dto.updatedAt() == null ? now : dto.updatedAt());
            row.setSort(dto.sort() == null ? sort : dto.sort());
            sort++;
            snapshot.templates().add(row);
        }
        requireWithinRows(snapshot.rowCount());
        String summary = "模板 " + snapshot.templates().size() + " 个";
        return write(ConfigKind.TEMPLATE, snapshot, ifMatch, operator, summary);
    }

    /**
     * 草稿写入统一收口：抢占 kind 级乐观锁 → 清空该 kind 草稿行 → 整体写入结构行 → 审计留痕。
     *
     * <p>与 {@link ConfigService#saveDraft} 的乐观锁口径完全一致（同一 {@code config_draft_meta} 行、
     * 同一版本语义），保证结构化端点与 YAML 端点的 {@code If-Match} 可混用。
     *
     * @param kind     配置种类
     * @param snapshot 待写入的结构行（{@code releaseId} 被强制为草稿作用域）
     * @param ifMatch  乐观锁期望版本
     * @param operator 操作人
     * @param summary  规模摘要（不含模板全文与 token）
     * @return 保存结果
     * @throws BusinessException 乐观锁冲突（4003）
     */
    private DraftSaveResult write(ConfigKind kind, StructuredSnapshot snapshot, Integer ifMatch,
                                  String operator, String summary) {
        Integer version = advanceLock(kind, ifMatch, operator);
        int rows = switch (kind) {
            case METADATA -> repository.replaceDraftMetadata(snapshot);
            case STARTER -> repository.replaceDraftStartersBySource(ConfigRepository.SOURCE_MANUAL, snapshot);
            case STARTER_SYNC -> repository.replaceDraftStartersBySource(ConfigRepository.SOURCE_SYNC, snapshot);
            case CONFLICT_RULE -> repository.replaceDraftConflictRules(snapshot);
            case TEMPLATE -> repository.replaceDraftTemplates(snapshot);
        };
        requireWithinRows(rows);
        repository.insertAudit(ACTION_UPDATE_STRUCTURED, kind, operator,
                kind.lowerName() + " 草稿已按结构化变更保存: " + summary + "；结构行 " + rows + " 行");
        log.info("结构化草稿已更新: kind={}, rows={}, operator={}, summary={}",
                kind.lowerName(), rows, operator, summary);
        // 模板集内容不进日志：规模摘要已含条目数，模板全文与潜在凭据绝不落日志/审计
        return new DraftSaveResult(kind.lowerName(), rows, version, summary);
    }

    /**
     * 推进 kind 级乐观锁（与 {@link ConfigService#saveDraft} 同口径）。
     *
     * @param kind     配置种类
     * @param ifMatch  期望版本（可空）
     * @param operator 操作人
     * @return 保存后的版本
     * @throws BusinessException 版本不匹配（4003）
     */
    private Integer advanceLock(ConfigKind kind, Integer ifMatch, String operator) {
        ConfigDraftMetaEntity meta = repository.findDraftMeta(kind);
        if (meta == null) {
            if (ifMatch != null) {
                throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY,
                        "草稿尚不存在，无法按版本 " + ifMatch + " 更新（请先 GET 获取最新版本）");
            }
            return repository.insertDraftMeta(kind, operator).getVersion();
        }
        if (ifMatch != null && !ifMatch.equals(meta.getVersion())) {
            throw new BusinessException(ErrorCode.CONFIG_CONCURRENT_MODIFY,
                    "草稿已被他人修改（当前版本 " + meta.getVersion() + "，请求基于版本 " + ifMatch + "）");
        }
        meta.setVersion(ifMatch == null ? meta.getVersion() : ifMatch);
        repository.updateDraftMeta(meta, operator);
        return meta.getVersion();
    }

    // ==================== 内部：读取与结构映射 ====================

    /**
     * 读取指定来源通道的 starter 清单（含依赖 / 联动 / 分组 / Boot 关联）。
     *
     * @param kind 配置种类（{@code STARTER} 或 {@code STARTER_SYNC}）
     * @return starter 项清单（按 sort 升序）
     */
    private List<StarterDto> readStarters(ConfigKind kind) {
        StructuredSnapshot rows = repository.readDraftScope(kind);
        return readStarters(rows);
    }

    /**
     * 由结构行快照映射 starter 项清单（内存分组，不查库）。
     *
     * @param rows 结构行快照
     * @return starter 项清单（按 sort 升序）
     */
    private List<StarterDto> readStarters(StructuredSnapshot rows) {
        Map<Long, List<CfgStarterDependencyEntity>> deps = group(rows.starterDependencies(),
                CfgStarterDependencyEntity::getStarterPk, CfgStarterDependencyEntity::getSort);
        Map<Long, List<CfgStarterRequireGroupEntity>> groups = group(rows.starterRequireGroups(),
                CfgStarterRequireGroupEntity::getStarterPk, CfgStarterRequireGroupEntity::getGroupIndex);
        Map<Long, List<CfgStarterRequireItemEntity>> items = group(rows.starterRequireItems(),
                CfgStarterRequireItemEntity::getGroupPk, CfgStarterRequireItemEntity::getSort);
        Map<Long, List<CfgStarterBootVersionEntity>> boots = group(rows.starterBootVersions(),
                CfgStarterBootVersionEntity::getStarterPk, null);
        Map<Long, List<CfgStarterLinkageTemplateEntity>> linkageTemplates = group(rows.starterLinkageTemplates(),
                CfgStarterLinkageTemplateEntity::getStarterPk, CfgStarterLinkageTemplateEntity::getSort);
        Map<Long, List<CfgStarterLinkageYmlEntity>> linkageYmls = group(rows.starterLinkageYmls(),
                CfgStarterLinkageYmlEntity::getStarterPk, CfgStarterLinkageYmlEntity::getSort);
        Map<Long, List<CfgStarterLinkageAbstractEntity>> linkageAbstracts = group(
                rows.starterLinkageAbstracts(), CfgStarterLinkageAbstractEntity::getStarterPk,
                CfgStarterLinkageAbstractEntity::getSort);

        List<StarterDto> result = new ArrayList<>();
        for (CfgStarterEntity row : rows.starters().stream()
                .sorted(Comparator.comparingInt(r -> sortKey(r.getSort()))).toList()) {
            List<DependencyDto> dependencies = deps.getOrDefault(row.getId(), List.of()).stream()
                    .map(d -> new DependencyDto(d.getGroupId(), d.getArtifactId(), d.getVersion())).toList();
            List<List<String>> requires = new ArrayList<>();
            for (CfgStarterRequireGroupEntity group : groups.getOrDefault(row.getId(), List.of())) {
                List<String> members = items.getOrDefault(group.getId(), List.of()).stream()
                        .map(CfgStarterRequireItemEntity::getRequiredStarterId).toList();
                if (!members.isEmpty()) {
                    requires.add(members);
                }
            }
            List<String> bootVersions = boots.getOrDefault(row.getId(), List.of()).stream()
                    .map(CfgStarterBootVersionEntity::getBootVersionId).toList();
            LinkageDto linkages = new LinkageDto(
                    linkageTemplates.getOrDefault(row.getId(), List.of()).stream()
                            .map(t -> new TemplateLinkageDto(t.getSource(), t.getTarget())).toList(),
                    linkageYmls.getOrDefault(row.getId(), List.of()).stream()
                            .map(y -> new YmlFragmentDto(y.getSource(),
                                    y.getMergeStrategy() == null ? "APPEND"
                                            : y.getMergeStrategy().toUpperCase(Locale.ROOT))).toList(),
                    linkageAbstracts.getOrDefault(row.getId(), List.of()).stream()
                            .map(a -> new AbstractClassDto(a.getFqcn(), a.getAlias())).toList());
            result.add(new StarterDto(row.getStarterId(), row.getCategory(), row.getMaintainer(),
                    row.getDescription(), Boolean.TRUE.equals(row.getRequired()),
                    Boolean.TRUE.equals(row.getHidden()), row.getConfigPackage(),
                    dependencies, requires, bootVersions, linkages, row.getSort()));
        }
        return result;
    }

    /**
     * 由 DTO 构建 starter 结构行快照（父行占位主键 + 子行父键引用，写入期换算）。
     *
     * @param dtos   starter 项清单
     * @param source 来源通道（{@code MANUAL} / {@code SYNC}）
     * @return 结构行快照（草稿作用域）
     * @throws BusinessException 结构非法（4002）
     */
    private StructuredSnapshot buildStarters(List<StarterUpsertDto> dtos, String source) {
        StructuredSnapshot snapshot = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        long placeholderPk = 0L;
        int sort = 0;
        Set<String> ids = new LinkedHashSet<>();
        Map<String, CfgStarterEntity> byId = new LinkedHashMap<>();
        for (StarterUpsertDto dto : dtos) {
            String id = requireText(dto.id(), "starters[].id");
            if (!ids.add(id)) {
                throw invalid("starter ID 重复: " + id);
            }
            CfgStarterEntity row = new CfgStarterEntity();
            // 显式占位主键（负数区间）：子行父键据此定位父行，由仓库层写入期换算
            row.setId(--placeholderPk);
            row.setStarterId(id);
            row.setCategory(requireText(dto.category(), "starters[].category"));
            row.setMaintainer(requireText(dto.maintainer(), "starters[].maintainer"));
            row.setDescription(dto.description());
            row.setRequired(Boolean.TRUE.equals(dto.required()));
            row.setHidden(Boolean.TRUE.equals(dto.hidden()));
            row.setConfigPackage(blankToNull(dto.configPackage()));
            row.setSource(source);
            row.setSort(dto.sort() == null ? sort : dto.sort());
            sort++;
            snapshot.starters().add(row);
            byId.put(id, row);
        }
        long groupPlaceholderPk = 0L;
        for (StarterUpsertDto dto : dtos) {
            CfgStarterEntity starterRow = byId.get(dto.id());
            int childSort = 0;
            for (DependencyDto dep : nullSafe(dto.dependencies())) {
                CfgStarterDependencyEntity row = new CfgStarterDependencyEntity();
                row.setStarterPk(starterRow.getId());
                row.setGroupId(requireText(dep.groupId(), "starters[].dependencies[].groupId"));
                row.setArtifactId(requireText(dep.artifactId(), "starters[].dependencies[].artifactId"));
                row.setVersion(blankToNull(dep.version()));
                row.setSort(childSort++);
                snapshot.starterDependencies().add(row);
            }
            int groupIndex = 0;
            for (List<String> group : nullSafe(dto.requires())) {
                List<String> members = nullSafe(group).stream()
                        .map(m -> requireText(m, "starters[].requires[][]")).toList();
                if (members.isEmpty()) {
                    continue;
                }
                CfgStarterRequireGroupEntity groupRow = new CfgStarterRequireGroupEntity();
                groupRow.setId(--groupPlaceholderPk);
                groupRow.setStarterPk(starterRow.getId());
                groupRow.setGroupIndex(groupIndex++);
                snapshot.starterRequireGroups().add(groupRow);
                int itemSort = 0;
                for (String member : members) {
                    CfgStarterRequireItemEntity itemRow = new CfgStarterRequireItemEntity();
                    itemRow.setGroupPk(groupRow.getId());
                    itemRow.setRequiredStarterId(member);
                    itemRow.setSort(itemSort++);
                    snapshot.starterRequireItems().add(itemRow);
                }
            }
            for (String bootVersionId : nullSafe(dto.bootVersions())) {
                CfgStarterBootVersionEntity row = new CfgStarterBootVersionEntity();
                row.setStarterPk(starterRow.getId());
                row.setBootVersionId(requireText(bootVersionId, "starters[].bootVersions[]"));
                snapshot.starterBootVersions().add(row);
            }
            LinkageDto linkages = dto.linkages();
            if (linkages != null) {
                int templateSort = 0;
                for (TemplateLinkageDto item : nullSafe(linkages.templates())) {
                    CfgStarterLinkageTemplateEntity row = new CfgStarterLinkageTemplateEntity();
                    row.setStarterPk(starterRow.getId());
                    row.setSource(requireText(item.source(), "starters[].linkages.templates[].source"));
                    row.setTarget(requireText(item.target(), "starters[].linkages.templates[].target"));
                    row.setSort(templateSort++);
                    snapshot.starterLinkageTemplates().add(row);
                }
                int ymlSort = 0;
                for (YmlFragmentDto item : nullSafe(linkages.ymlFragments())) {
                    CfgStarterLinkageYmlEntity row = new CfgStarterLinkageYmlEntity();
                    row.setStarterPk(starterRow.getId());
                    row.setSource(requireText(item.source(), "starters[].linkages.ymlFragments[].source"));
                    row.setMergeStrategy(item.mergeStrategy() == null || item.mergeStrategy().isBlank()
                            ? "APPEND" : item.mergeStrategy().trim().toUpperCase(Locale.ROOT));
                    row.setSort(ymlSort++);
                    snapshot.starterLinkageYmls().add(row);
                }
                int abstractSort = 0;
                for (AbstractClassDto item : nullSafe(linkages.abstractClasses())) {
                    CfgStarterLinkageAbstractEntity row = new CfgStarterLinkageAbstractEntity();
                    row.setStarterPk(starterRow.getId());
                    row.setFqcn(requireText(item.fqcn(), "starters[].linkages.abstractClasses[].fqcn"));
                    row.setAlias(item.alias());
                    row.setSort(abstractSort++);
                    snapshot.starterLinkageAbstracts().add(row);
                }
            }
        }
        return snapshot;
    }

    // ==================== 内部：小工具 ====================

    /**
     * 还原 Boot 行的框架父 POM（三字段齐全才生效，与写入口径一致）。
     *
     * @param row Boot 版本行
     * @return 框架父 POM；未完整配置返回 {@code null}
     */
    private static FrameworkParentDto frameworkParentOf(CfgBootVersionEntity row) {
        if (row.getFpGroup() == null || row.getFpArtifact() == null || row.getFpVersion() == null) {
            return null;
        }
        return new FrameworkParentDto(row.getFpGroup(), row.getFpArtifact(), row.getFpVersion());
    }

    /**
     * 写入框架父 POM 三字段（缺一即拒绝，避免半配置状态落库）。
     *
     * @param row    Boot 版本行
     * @param parent 框架父 POM DTO（可空）
     * @throws BusinessException 三字段不齐（4002）
     */
    private static void applyFrameworkParent(CfgBootVersionEntity row, FrameworkParentDto parent) {
        if (parent == null) {
            return;
        }
        String group = blankToNull(parent.groupId());
        String artifact = blankToNull(parent.artifactId());
        String version = blankToNull(parent.version());
        if (group == null || artifact == null || version == null) {
            throw invalid("bootVersions[].frameworkParent 字段不完整，需 groupId/artifactId/version 三项齐全");
        }
        row.setFpGroup(group);
        row.setFpArtifact(artifact);
        row.setFpVersion(version);
    }

    /**
     * 按父键分组（内存分组，不查库）并按排序键定序。
     *
     * @param rows   待分组行
     * @param keyOf  父键提取
     * @param sortOf 排序键提取（可空 = 保持查询顺序）
     * @param <T>    行类型
     * @return 父键 → 行列表
     */
    private static <T> Map<Long, List<T>> group(List<T> rows,
                                                java.util.function.Function<T, Long> keyOf,
                                                java.util.function.Function<T, Integer> sortOf) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        for (T row : rows) {
            Long key = keyOf.apply(row);
            if (key != null) {
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        if (sortOf != null) {
            grouped.values().forEach(list -> list.sort(Comparator.comparingInt(
                    r -> sortKey(sortOf.apply(r)))));
        }
        return grouped;
    }

    /**
     * 按父键收集字符串维度（去重保序，内存分组）。
     *
     * @param rows    待分组行
     * @param keyOf   父键提取
     * @param valueOf 值提取
     * @param <T>     行类型
     * @return 父键 → 值清单
     */
    private static <T> Map<Long, List<String>> groupStrings(List<T> rows,
                                                            java.util.function.Function<T, Long> keyOf,
                                                            java.util.function.Function<T, String> valueOf) {
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        for (T row : rows) {
            Long key = keyOf.apply(row);
            if (key != null) {
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(valueOf.apply(row));
            }
        }
        return grouped;
    }

    /**
     * 排序键归一（null 排最后）。
     *
     * @param sort 排序列值
     * @return 可比较的整数
     */
    private static int sortKey(Integer sort) {
        return sort == null ? Integer.MAX_VALUE : sort;
    }

    /**
     * 空集合归一。
     *
     * @param list 入参集合（可空）
     * @param <T>  元素类型
     * @return 非空列表
     */
    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * 必填文本断言（去空白）。
     *
     * @param value 原始值（可空）
     * @param field 字段路径（错误明细用）
     * @return 去空白后的非空文本
     * @throws BusinessException 缺失或空白（4002）
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("缺少必需字段: " + field);
        }
        return value.trim();
    }

    /**
     * 请求体必填断言（空请求体收敛为 4002，而非 NPE 500）。
     *
     * @param request 请求体（可空）
     * @param module  模块名（错误明细用）
     * @throws BusinessException 请求体缺失（4002）
     */
    private static void requireRequest(Object request, String module) {
        if (request == null) {
            throw invalid("缺少请求体: " + module);
        }
    }

    /**
     * 操作人必填断言。
     *
     * @param operator 操作人（可空）
     * @throws BusinessException 缺失（4002）
     */
    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw invalid("缺少必需字段: operator");
        }
    }

    /**
     * 空白归一为 {@code null}。
     *
     * @param value 原始值
     * @return 去空白后的值；空白返回 {@code null}
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 条目数量上限断言。
     *
     * @param field 字段路径（错误明细用）
     * @param count 条目数
     * @throws BusinessException 超限（4002）
     */
    private static void requireWithin(String field, int count) {
        if (count > MAX_ITEMS) {
            throw invalid(field + " 条目数超限（最多 " + MAX_ITEMS + " 项，当前 " + count + "）");
        }
    }

    /**
     * 结构行数量上限断言（含子行，防误提交爆炸性请求）。
     *
     * @param rows 结构行数
     * @throws BusinessException 超限（4002）
     */
    private static void requireWithinRows(int rows) {
        if (rows > MAX_ROWS) {
            throw invalid("结构行数超限（最多 " + MAX_ROWS + " 行，当前 " + rows + "）");
        }
    }

    /**
     * 模板内容长度上限断言。
     *
     * @param name    模板名（错误明细用）
     * @param content 模板全文
     * @throws BusinessException 超长（4002）
     */
    private static void requireTemplateLength(String name, String content) {
        if (content.length() > MAX_TEMPLATE_LENGTH) {
            throw invalid("模板 " + name + " 内容超长（最多 " + MAX_TEMPLATE_LENGTH + " 字符）");
        }
    }

    /**
     * 构造 4002 结构非法异常。
     *
     * @param message 明细
     * @return 业务异常（错误码 4002）
     */
    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED, message);
    }
}
