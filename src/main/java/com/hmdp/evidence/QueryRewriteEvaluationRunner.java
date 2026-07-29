package com.hmdp.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.AiPromptMessage;
import com.hmdp.ai.AiQueryPreprocessor;
import com.hmdp.ai.AiQueryRewriteMode;
import com.hmdp.ai.AiRetrievalQueryPlan;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.config.AiEvaluationProperties;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopKnowledgeService;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.evaluation", name = "enabled", havingValue = "true")
public class QueryRewriteEvaluationRunner implements ApplicationRunner {

    private static final int EXPECTED_CASE_COUNT = 50;

    private static final List<String> RESULT_COLUMNS = Arrays.asList(
            "expected_shop_ids",
            "actual_mode",
            "rewritten_queries",
            "model_called",
            "valid_model_output",
            "mode_match",
            "model_call_match",
            "constraint_preserved",
            "clarification_correct",
            "pass_through_no_regression",
            "baseline_vector_top3",
            "rewritten_vector_top3",
            "baseline_hybrid_top3",
            "rewritten_hybrid_top3",
            "baseline_vector_hit_at_1",
            "baseline_vector_hit_at_3",
            "rewritten_vector_hit_at_1",
            "rewritten_vector_hit_at_3",
            "baseline_hybrid_hit_at_1",
            "baseline_hybrid_hit_at_3",
            "rewritten_hybrid_hit_at_1",
            "rewritten_hybrid_hit_at_3",
            "original_chars",
            "rewritten_chars",
            "rewrite_ms",
            "fixture_error"
    );

    @Resource
    private AiEvaluationProperties evaluationProperties;

    @Resource
    private AiQueryPreprocessor queryPreprocessor;

    @Resource
    private IShopKnowledgeService shopKnowledgeService;

    @Resource
    private IShopService shopService;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        String mode = evaluationProperties.getMode();
        if (!"query-rewrite".equalsIgnoreCase(mode) && !"all".equalsIgnoreCase(mode)) {
            return;
        }
        Path casesPath = Paths.get(evaluationProperties.getQueryRewriteCasesPath());
        Path reportPath = Paths.get(evaluationProperties.getQueryRewriteReportPath());
        if (!Files.exists(casesPath)) {
            log.warn("Query rewrite evaluation skipped because the case file does not exist: {}",
                    casesPath.toAbsolutePath());
            return;
        }
        try {
            evaluate(casesPath, reportPath);
        } catch (Exception e) {
            log.error("Query rewrite evaluation failed. Existing evidence files were left unchanged.", e);
        }
    }

    private void evaluate(Path casesPath, Path reportPath) throws Exception {
        List<String> lines = Files.readAllLines(casesPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("Query rewrite evaluation case file is empty");
        }
        List<String> headers = parseCsvLine(lines.get(0));
        ensureResultColumns(headers);
        Map<String, Integer> indexes = headerIndexes(headers);
        validateInputColumns(indexes);

        List<Shop> shops = shopService.list();
        Map<Long, String> shopNamesById = shops.stream()
                .collect(Collectors.toMap(Shop::getId, Shop::getName, (left, right) -> left, LinkedHashMap::new));

        List<EvaluationRecord> records = new ArrayList<>();
        List<String> output = new ArrayList<>();
        output.add(toCsvLine(headers));
        int caseCount = 0;
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            if (lines.get(lineNumber).trim().isEmpty()) {
                continue;
            }
            caseCount++;
            List<String> row = new ArrayList<>(parseCsvLine(lines.get(lineNumber)));
            while (row.size() < headers.size()) {
                row.add("");
            }
            EvaluationRecord record = evaluateRow(row, indexes, shops);
            records.add(record);
            writeRecord(row, indexes, record);
            output.add(toCsvLine(row));
        }
        if (caseCount != EXPECTED_CASE_COUNT) {
            throw new IllegalStateException("Expected exactly 50 query rewrite cases, actual=" + caseCount);
        }

        String report = buildReport(records, shopNamesById);
        Files.createDirectories(reportPath.toAbsolutePath().getParent());
        Files.write(casesPath, output, StandardCharsets.UTF_8);
        Files.write(reportPath, report.getBytes(StandardCharsets.UTF_8));
        log.info("Query rewrite evaluation completed, cases={}, report={}",
                caseCount, reportPath.toAbsolutePath());
    }

    private EvaluationRecord evaluateRow(List<String> row, Map<String, Integer> indexes,
                                         List<Shop> shops) throws Exception {
        EvaluationRecord record = new EvaluationRecord();
        record.id = value(row, indexes, "id");
        record.split = value(row, indexes, "split");
        record.scenario = value(row, indexes, "scenario");
        record.question = value(row, indexes, "current_question");
        record.expectedMode = AiQueryRewriteMode.valueOf(
                value(row, indexes, "expected_mode").trim().toUpperCase());
        record.expectedModelCalled = Boolean.parseBoolean(value(row, indexes, "expected_model_called"));
        record.expectedShopIds = resolveExpectedShopIds(
                value(row, indexes, "expected_shop_names"), shops, record);
        record.requiredTerms = splitPipe(value(row, indexes, "required_terms"));

        List<AiPromptMessage> history = parseHistory(value(row, indexes, "history_json"));
        record.plan = queryPreprocessor.preprocess(
                null, null, null, record.question, null, history);

        record.baselineVector = shopIds(shopKnowledgeService.searchRelevantShops(record.question, false));
        record.baselineHybrid = shopIds(shopKnowledgeService.searchRelevantShops(record.question, true));
        if (record.plan.requiresClarification()) {
            record.rewrittenVector = Collections.emptyList();
            record.rewrittenHybrid = Collections.emptyList();
        } else if (record.plan.getMode() == AiQueryRewriteMode.PASS_THROUGH) {
            record.rewrittenVector = record.baselineVector;
            record.rewrittenHybrid = record.baselineHybrid;
        } else {
            record.rewrittenVector = shopIds(
                    shopKnowledgeService.searchRelevantShops(record.plan.getQueries(), false));
            record.rewrittenHybrid = shopIds(
                    shopKnowledgeService.searchRelevantShops(record.plan.getQueries(), true));
        }
        record.modeMatch = record.expectedMode == record.plan.getMode();
        record.modelCallMatch = record.expectedModelCalled == record.plan.isModelCalled();
        record.constraintPreserved = constraintsPreserved(record.requiredTerms, record.plan.getQueries());
        record.clarificationCorrect = record.expectedMode != AiQueryRewriteMode.CLARIFY
                || record.plan.requiresClarification();
        record.passThroughNoRegression = record.expectedMode != AiQueryRewriteMode.PASS_THROUGH
                || (record.plan.getMode() == AiQueryRewriteMode.PASS_THROUGH
                && record.baselineVector.equals(record.rewrittenVector)
                && record.baselineHybrid.equals(record.rewrittenHybrid));
        return record;
    }

    private Set<Long> resolveExpectedShopIds(String expectedNames, List<Shop> shops,
                                             EvaluationRecord record) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String expectedName : splitPipe(expectedNames)) {
            String normalizedExpected = normalize(expectedName);
            List<Shop> matches = shops.stream()
                    .filter(shop -> {
                        String normalizedActual = normalize(shop.getName());
                        return normalizedActual.equals(normalizedExpected)
                                || normalizedActual.contains(normalizedExpected)
                                || normalizedExpected.contains(normalizedActual);
                    })
                    .collect(Collectors.toList());
            if (matches.isEmpty()) {
                record.fixtureErrors.add("missing shop: " + expectedName);
                continue;
            }
            for (Shop match : matches) {
                ids.add(match.getId());
            }
        }
        return ids;
    }

    private List<AiPromptMessage> parseHistory(String historyJson) throws IOException {
        if (historyJson == null || historyJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        JsonNode root = objectMapper.readTree(historyJson);
        if (!root.isArray()) {
            throw new IllegalArgumentException("history_json must be a JSON array");
        }
        List<AiPromptMessage> history = new ArrayList<>();
        for (JsonNode item : root) {
            String role = item.path("role").asText("");
            String content = item.path("content").asText("");
            if (!role.trim().isEmpty() && !content.trim().isEmpty()) {
                history.add(new AiPromptMessage(role, content));
            }
        }
        return history;
    }

    private boolean constraintsPreserved(List<String> requiredTerms, List<String> queries) {
        if (requiredTerms.isEmpty()) {
            return true;
        }
        String normalizedQueries = normalize(String.join(" ", queries));
        for (String term : requiredTerms) {
            if (!normalizedQueries.contains(normalize(term))) {
                return false;
            }
        }
        return true;
    }

    private void writeRecord(List<String> row, Map<String, Integer> indexes,
                             EvaluationRecord record) {
        setValue(row, indexes, "expected_shop_ids", joinIds(new ArrayList<>(record.expectedShopIds)));
        setValue(row, indexes, "actual_mode", record.plan.getMode().name());
        setValue(row, indexes, "rewritten_queries", String.join(" || ", record.plan.getQueries()));
        setValue(row, indexes, "model_called", String.valueOf(record.plan.isModelCalled()));
        setValue(row, indexes, "valid_model_output", String.valueOf(record.plan.isValidModelOutput()));
        setValue(row, indexes, "mode_match", String.valueOf(record.modeMatch));
        setValue(row, indexes, "model_call_match", String.valueOf(record.modelCallMatch));
        setValue(row, indexes, "constraint_preserved", String.valueOf(record.constraintPreserved));
        setValue(row, indexes, "clarification_correct", String.valueOf(record.clarificationCorrect));
        setValue(row, indexes, "pass_through_no_regression", String.valueOf(record.passThroughNoRegression));
        setValue(row, indexes, "baseline_vector_top3", joinIds(record.baselineVector));
        setValue(row, indexes, "rewritten_vector_top3", joinIds(record.rewrittenVector));
        setValue(row, indexes, "baseline_hybrid_top3", joinIds(record.baselineHybrid));
        setValue(row, indexes, "rewritten_hybrid_top3", joinIds(record.rewrittenHybrid));
        writeHits(row, indexes, "baseline_vector", record.baselineVector, record.expectedShopIds);
        writeHits(row, indexes, "rewritten_vector", record.rewrittenVector, record.expectedShopIds);
        writeHits(row, indexes, "baseline_hybrid", record.baselineHybrid, record.expectedShopIds);
        writeHits(row, indexes, "rewritten_hybrid", record.rewrittenHybrid, record.expectedShopIds);
        setValue(row, indexes, "original_chars", String.valueOf(record.plan.getOriginalChars()));
        setValue(row, indexes, "rewritten_chars", String.valueOf(record.plan.getRewrittenChars()));
        setValue(row, indexes, "rewrite_ms", String.valueOf(record.plan.getRewriteMs()));
        setValue(row, indexes, "fixture_error", String.join(" | ", record.fixtureErrors));
    }

    private void writeHits(List<String> row, Map<String, Integer> indexes, String prefix,
                           List<Long> actualIds, Set<Long> expectedIds) {
        if (expectedIds.isEmpty()) {
            setValue(row, indexes, prefix + "_hit_at_1", "");
            setValue(row, indexes, prefix + "_hit_at_3", "");
            return;
        }
        setValue(row, indexes, prefix + "_hit_at_1",
                String.valueOf(!actualIds.isEmpty() && expectedIds.contains(actualIds.get(0))));
        setValue(row, indexes, prefix + "_hit_at_3",
                String.valueOf(actualIds.stream().anyMatch(expectedIds::contains)));
    }

    private String buildReport(List<EvaluationRecord> allRecords, Map<Long, String> shopNamesById) {
        List<EvaluationRecord> records = allRecords.stream()
                .filter(record -> "TEST".equalsIgnoreCase(record.split))
                .collect(Collectors.toList());
        List<EvaluationRecord> retrievalRecords = records.stream()
                .filter(record -> !record.expectedShopIds.isEmpty() && record.fixtureErrors.isEmpty())
                .collect(Collectors.toList());
        List<EvaluationRecord> modelRecords = records.stream()
                .filter(record -> record.plan.isModelCalled())
                .collect(Collectors.toList());
        List<Long> latencies = modelRecords.stream()
                .map(record -> record.plan.getRewriteMs())
                .sorted()
                .collect(Collectors.toList());

        StringBuilder report = new StringBuilder();
        report.append("# 查询重写与检索输入评测\n\n");
        report.append("> 生成时间：").append(LocalDateTime.now()).append("  \n");
        report.append("> 数据快照：MySQL 店铺数 ").append(shopNamesById.size()).append("  \n");
        report.append("> 口径：10 条开发集不计入最终指标，以下结果仅统计 40 条固定测试集。\n\n");
        report.append("## 评测目标\n\n");
        report.append("对比同一知识库下的“原始问题直接检索”和“查询预处理后检索”。")
                .append("Hit@K 只衡量检索是否命中预期店铺，不代表最终回答准确率。\n\n");
        report.append("## 汇总结果\n\n");
        report.append("| 指标 | 原始输入 | 查询预处理后 |\n");
        report.append("|---|---:|---:|\n");
        appendHitRow(report, "纯向量 Hit@1", retrievalRecords,
                record -> hitAt1(record.baselineVector, record.expectedShopIds),
                record -> hitAt1(record.rewrittenVector, record.expectedShopIds));
        appendHitRow(report, "纯向量 Hit@3", retrievalRecords,
                record -> hitAt3(record.baselineVector, record.expectedShopIds),
                record -> hitAt3(record.rewrittenVector, record.expectedShopIds));
        appendHitRow(report, "生产混合检索 Hit@1", retrievalRecords,
                record -> hitAt1(record.baselineHybrid, record.expectedShopIds),
                record -> hitAt1(record.rewrittenHybrid, record.expectedShopIds));
        appendHitRow(report, "生产混合检索 Hit@3", retrievalRecords,
                record -> hitAt3(record.baselineHybrid, record.expectedShopIds),
                record -> hitAt3(record.rewrittenHybrid, record.expectedShopIds));

        report.append("\n## 查询预处理行为\n\n");
        report.append("- 模式判断准确率：").append(rate(records, record -> record.modeMatch)).append('\n');
        report.append("- 模型调用判断准确率：").append(rate(records, record -> record.modelCallMatch)).append('\n');
        report.append("- 约束保留率：").append(rate(records, record -> record.constraintPreserved)).append('\n');
        report.append("- 歧义问题正确澄清率：").append(rate(
                records.stream().filter(record -> record.expectedMode == AiQueryRewriteMode.CLARIFY)
                        .collect(Collectors.toList()),
                record -> record.clarificationCorrect)).append('\n');
        report.append("- 短问题无回退率：").append(rate(
                records.stream().filter(record -> record.expectedMode == AiQueryRewriteMode.PASS_THROUGH)
                        .collect(Collectors.toList()),
                record -> record.passThroughNoRegression)).append('\n');
        report.append("- 有效模型输出率：").append(rate(modelRecords,
                record -> record.plan.isValidModelOutput())).append('\n');
        report.append("- 测试集额外模型调用比例：")
                .append(percent(modelRecords.size(), records.size())).append('\n');
        report.append("- 重写耗时：平均 ").append(average(latencies))
                .append(" ms，P95 ").append(percentile95(latencies)).append(" ms\n");

        List<EvaluationRecord> longRecords = records.stream()
                .filter(record -> "long-noisy".equalsIgnoreCase(record.scenario))
                .collect(Collectors.toList());
        long originalChars = longRecords.stream().mapToLong(record -> record.plan.getOriginalChars()).sum();
        long rewrittenChars = longRecords.stream().mapToLong(record -> record.plan.getRewrittenChars()).sum();
        report.append("- 长问题字符压缩率：")
                .append(originalChars == 0 ? "N/A"
                        : String.format("%.2f%%", (1D - rewrittenChars * 1D / originalChars) * 100D))
                .append('\n');

        report.append("\n## Bad Case\n\n");
        List<EvaluationRecord> badCases = records.stream()
                .filter(record -> !record.fixtureErrors.isEmpty()
                        || !record.modeMatch
                        || !record.constraintPreserved
                        || (!record.expectedShopIds.isEmpty()
                        && !hitAt3(record.rewrittenHybrid, record.expectedShopIds)))
                .collect(Collectors.toList());
        if (badCases.isEmpty()) {
            report.append("当前固定测试集未发现 Bad Case。\n");
        } else {
            report.append("| ID | 场景 | 实际模式 | 重写查询 | 生产 Top3 | 问题 |\n");
            report.append("|---|---|---|---|---|---|\n");
            for (EvaluationRecord record : badCases) {
                List<String> issues = new ArrayList<>();
                if (!record.fixtureErrors.isEmpty()) {
                    issues.add(String.join("；", record.fixtureErrors));
                }
                if (!record.modeMatch) {
                    issues.add("模式不匹配");
                }
                if (!record.constraintPreserved) {
                    issues.add("约束丢失");
                }
                if (!record.expectedShopIds.isEmpty()
                        && !hitAt3(record.rewrittenHybrid, record.expectedShopIds)) {
                    issues.add("Hit@3 未命中");
                }
                report.append('|').append(record.id)
                        .append('|').append(record.scenario)
                        .append('|').append(record.plan.getMode())
                        .append('|').append(escapeMarkdown(String.join(" / ", record.plan.getQueries())))
                        .append('|').append(names(record.rewrittenHybrid, shopNamesById))
                        .append('|').append(String.join("；", issues)).append("|\n");
            }
        }

        report.append("\n## 运行方式\n\n");
        report.append("设置真实聊天模型、Embedding、MySQL 和 Qdrant 后启动：\n\n");
        report.append("```text\n");
        report.append("AI_EVALUATION_ENABLED=true\n");
        report.append("AI_EVALUATION_MODE=query-rewrite\n");
        report.append("```\n\n");
        report.append("运行器会更新案例 CSV 的实际结果列，并重新生成本报告。")
                .append("不得使用固定测试集反复调整提示词或阈值。\n");
        return report.toString();
    }

    private void appendHitRow(StringBuilder report, String name, List<EvaluationRecord> records,
                              RecordPredicate baseline, RecordPredicate rewritten) {
        report.append('|').append(name).append('|')
                .append(rate(records, baseline)).append('|')
                .append(rate(records, rewritten)).append("|\n");
    }

    private boolean hitAt1(List<Long> actual, Set<Long> expected) {
        return !actual.isEmpty() && expected.contains(actual.get(0));
    }

    private boolean hitAt3(List<Long> actual, Set<Long> expected) {
        return actual.stream().anyMatch(expected::contains);
    }

    private String rate(List<EvaluationRecord> records, RecordPredicate predicate) {
        if (records.isEmpty()) {
            return "N/A";
        }
        int hits = 0;
        for (EvaluationRecord record : records) {
            if (predicate.test(record)) {
                hits++;
            }
        }
        return percent(hits, records.size()) + " (" + hits + "/" + records.size() + ")";
    }

    private String percent(int numerator, int denominator) {
        return denominator == 0 ? "N/A" : String.format("%.2f%%", numerator * 100D / denominator);
    }

    private long average(List<Long> values) {
        return values.isEmpty() ? 0L
                : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0D));
    }

    private long percentile95(List<Long> sortedValues) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * 0.95D) - 1);
        return sortedValues.get(index);
    }

    private String names(List<Long> ids, Map<Long, String> namesById) {
        return ids.stream()
                .map(id -> namesById.getOrDefault(id, String.valueOf(id)))
                .collect(Collectors.joining(" / "));
    }

    private String escapeMarkdown(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private List<Long> shopIds(List<ShopKnowledge> knowledge) {
        if (knowledge == null) {
            return Collections.emptyList();
        }
        return knowledge.stream()
                .map(ShopKnowledge::getShopId)
                .filter(id -> id != null)
                .limit(3)
                .collect(Collectors.toList());
    }

    private List<String> splitPipe(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split("\\|"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toList());
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]", "");
    }

    private void ensureResultColumns(List<String> headers) {
        for (String column : RESULT_COLUMNS) {
            if (!headers.contains(column)) {
                headers.add(column);
            }
        }
    }

    private void validateInputColumns(Map<String, Integer> indexes) {
        for (String column : Arrays.asList(
                "id", "split", "scenario", "history_json", "current_question",
                "expected_mode", "expected_model_called", "expected_shop_names", "required_terms"
        )) {
            if (!indexes.containsKey(column)) {
                throw new IllegalStateException("Missing query rewrite evaluation column: " + column);
            }
        }
    }

    private Map<String, Integer> headerIndexes(List<String> headers) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            indexes.put(headers.get(index), index);
        }
        return indexes;
    }

    private String value(List<String> row, Map<String, Integer> indexes, String key) {
        Integer index = indexes.get(key);
        return index == null || index >= row.size() ? "" : row.get(index);
    }

    private void setValue(List<String> row, Map<String, Integer> indexes, String key, String value) {
        Integer index = indexes.get(key);
        while (row.size() <= index) {
            row.add("");
        }
        row.set(index, value == null ? "" : value);
    }

    private String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining("|"));
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        values.add(value.toString());
        return values;
    }

    private String toCsvLine(List<String> values) {
        return values.stream().map(this::escapeCsv).collect(Collectors.joining(","));
    }

    private String escapeCsv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private interface RecordPredicate {
        boolean test(EvaluationRecord record);
    }

    private static class EvaluationRecord {
        private String id;
        private String split;
        private String scenario;
        private String question;
        private AiQueryRewriteMode expectedMode;
        private boolean expectedModelCalled;
        private Set<Long> expectedShopIds = new LinkedHashSet<>();
        private List<String> requiredTerms = Collections.emptyList();
        private AiRetrievalQueryPlan plan;
        private List<Long> baselineVector = Collections.emptyList();
        private List<Long> rewrittenVector = Collections.emptyList();
        private List<Long> baselineHybrid = Collections.emptyList();
        private List<Long> rewrittenHybrid = Collections.emptyList();
        private boolean modeMatch;
        private boolean modelCallMatch;
        private boolean constraintPreserved;
        private boolean clarificationCorrect;
        private boolean passThroughNoRegression;
        private List<String> fixtureErrors = new ArrayList<>();
    }
}
