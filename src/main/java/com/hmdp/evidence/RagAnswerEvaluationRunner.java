package com.hmdp.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.AiPromptMessage;
import com.hmdp.ai.AiRagEvaluationRequest;
import com.hmdp.ai.AiRagEvaluationResult;
import com.hmdp.ai.AiToolExecution;
import com.hmdp.config.AiEvaluationProperties;
import com.hmdp.entity.Shop;
import com.hmdp.service.IAiRagEvaluationService;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fixture-based RAG orchestration regression runner.
 *
 * It injects static history into prompt assembly, so it is useful for deterministic
 * regression checks but is not a real multi-turn conversation test. Conversation-level
 * evidence is generated separately without changing Agent behavior.
 */
@Slf4j
@Component
@Order(200)
@ConditionalOnProperty(prefix = "ai.evaluation", name = "enabled", havingValue = "true")
public class RagAnswerEvaluationRunner implements ApplicationRunner {

    @Resource
    private AiEvaluationProperties evaluationProperties;

    @Resource
    private IAiRagEvaluationService aiRagEvaluationService;

    @Resource
    private IShopService shopService;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        String mode = evaluationProperties.getMode();
        if (!"answer".equalsIgnoreCase(mode) && !"all".equalsIgnoreCase(mode)) {
            return;
        }
        Path sourcePath = Paths.get(evaluationProperties.getRagAnswerSourcePath());
        if (!Files.exists(sourcePath)) {
            log.warn("RAG answer evaluation skipped because source cases do not exist: {}",
                    sourcePath.toAbsolutePath());
            return;
        }
        try {
            evaluate(sourcePath, evaluationProperties.getRagAnswerSourceSplit(),
                    Math.max(1, evaluationProperties.getRagAnswerExpectedCaseCount()),
                    Paths.get(evaluationProperties.getRagAnswerCasesPath()),
                    Paths.get(evaluationProperties.getRagAnswerReportPath()));
        } catch (Exception e) {
            log.error("RAG answer evaluation failed. Existing evidence files were left unchanged.", e);
        }
    }

    private void evaluate(Path sourcePath, String sourceSplit, int expectedCaseCount,
                          Path outputPath, Path reportPath) throws Exception {
        List<String> lines = Files.readAllLines(sourcePath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("Query rewrite source case file is empty");
        }
        List<String> headers = parseCsvLine(lines.get(0));
        Map<String, Integer> indexes = headerIndexes(headers);
        requireColumns(indexes, "id", "split", "scenario", "history_json", "current_question",
                "expected_mode", "expected_shop_names", "required_terms");

        List<Shop> shops = shopService.list();
        List<AnswerRecord> records = new ArrayList<>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            if (lines.get(lineNumber).trim().isEmpty()) {
                continue;
            }
            List<String> row = parseCsvLine(lines.get(lineNumber));
            if (!sourceSplit.equalsIgnoreCase(value(row, indexes, "split"))) {
                continue;
            }
            records.add(evaluateCase(row, indexes, shops));
        }
        if (records.size() != expectedCaseCount) {
            throw new IllegalStateException("Expected " + expectedCaseCount
                    + " " + sourceSplit + " cases, actual=" + records.size());
        }
        writeCsv(outputPath, records);
        writeReport(reportPath, sourcePath, sourceSplit, records);
        log.info("Fixture RAG orchestration regression completed, cases={}, output={}, report={}",
                records.size(), outputPath.toAbsolutePath(), reportPath.toAbsolutePath());
    }

    private AnswerRecord evaluateCase(List<String> row, Map<String, Integer> indexes,
                                      List<Shop> shops) throws Exception {
        AnswerRecord record = new AnswerRecord();
        record.caseId = value(row, indexes, "id");
        record.scenario = value(row, indexes, "scenario");
        record.question = value(row, indexes, "current_question");
        record.expectedMode = value(row, indexes, "expected_mode");
        record.expectedShopNames = splitPipe(value(row, indexes, "expected_shop_names"));
        record.requiredTerms = splitPipe(value(row, indexes, "required_terms"));
        record.history = parseHistory(value(row, indexes, "history_json"));

        AiRagEvaluationResult result = aiRagEvaluationService.evaluate(
                new AiRagEvaluationRequest(record.question, record.history));
        record.actualMode = result.getMode();
        record.retrievalQueries = result.getRetrievalQueries();
        record.rewriteModelCalled = result.isRewriteModelCalled();
        record.validRewriteOutput = result.isValidRewriteOutput();
        record.outcome = result.getOutcome();
        record.requestId = result.getRequestId();
        record.traceId = result.getTraceId();
        record.retrievedShopIds = result.getRetrievedShops().stream()
                .map(knowledge -> knowledge.getShopId())
                .filter(id -> id != null)
                .limit(3)
                .collect(Collectors.toList());
        record.toolNames = result.getToolExecutions().stream()
                .map(AiToolExecution::getToolName)
                .filter(name -> name != null && !name.trim().isEmpty())
                .collect(Collectors.toList());
        record.retrievedEvidence = result.getRetrievedShops().stream()
                .map(knowledge -> "shopId=" + knowledge.getShopId() + ", "
                        + limitText(knowledge.getContent(), 1200))
                .collect(Collectors.joining("\n---\n"));
        record.toolEvidence = result.getToolExecutions().stream()
                .map(execution -> execution.getToolName() + ":\n"
                        + limitText(execution.getResultContent(), 1600))
                .collect(Collectors.joining("\n---\n"));
        record.answer = result.getAnswer();
        record.retrievalMs = result.getRetrievalMs();
        record.toolMs = result.getToolMs();
        record.firstTokenMs = result.getFirstTokenMs();
        record.totalMs = result.getTotalMs();
        record.error = result.getErrorMessage();

        Set<String> expectedNormalized = record.expectedShopNames.stream()
                .map(this::normalize).collect(Collectors.toCollection(LinkedHashSet::new));
        String normalizedAnswer = normalize(record.answer);
        record.clarificationCorrect = "CLARIFY".equalsIgnoreCase(record.expectedMode)
                ? "CLARIFIED".equalsIgnoreCase(record.outcome) : true;
        record.expectedEntityMentioned = expectedNormalized.isEmpty() || expectedNormalized.stream()
                .anyMatch(normalizedAnswer::contains);
        record.allExpectedEntitiesMentioned = !requiresAllExpectedEntities(record)
                || expectedNormalized.stream().allMatch(normalizedAnswer::contains);
        record.requiredTools = clarificationCase(record)
                ? Collections.<String>emptyList() : requiredTools(record.question);
        record.requiredToolsInvoked = record.requiredTools.stream().allMatch(record.toolNames::contains);
        record.unexpectedKnownShopNames = unexpectedKnownShopNames(record, shops, normalizedAnswer);

        boolean clarificationCase = clarificationCase(record);
        record.automaticPass = clarificationCase
                ? record.clarificationCorrect && record.toolNames.isEmpty()
                : "ANSWERED".equalsIgnoreCase(record.outcome)
                && record.expectedEntityMentioned
                && record.allExpectedEntitiesMentioned
                && record.requiredToolsInvoked
                && record.unexpectedKnownShopNames.isEmpty()
                && isBlank(record.error);
        record.manualReview = clarificationCase
                ? "Review clarification wording only"
                : "Verify voucher/address/price/opening-hours facts against retrieved evidence and tool results";
        return record;
    }

    private List<String> unexpectedKnownShopNames(AnswerRecord record, List<Shop> shops,
                                                   String normalizedAnswer) {
        Set<Long> allowedIds = new LinkedHashSet<>(record.retrievedShopIds);
        for (String expected : record.expectedShopNames) {
            for (Shop shop : shops) {
                if (namesMatch(expected, shop.getName())) {
                    allowedIds.add(shop.getId());
                }
            }
        }
        List<Shop> byNameLength = new ArrayList<>(shops);
        byNameLength.sort(Comparator.comparingInt((Shop shop) -> normalize(shop.getName()).length()).reversed());
        List<String> unexpected = new ArrayList<>();
        for (Shop shop : byNameLength) {
            String normalizedName = normalize(shop.getName());
            if (!normalizedName.isEmpty() && normalizedAnswer.contains(normalizedName)
                    && !allowedIds.contains(shop.getId())) {
                unexpected.add(shop.getName());
            }
        }
        return unexpected;
    }

    private boolean requiresAllExpectedEntities(AnswerRecord record) {
        return "multi-intent".equalsIgnoreCase(record.scenario);
    }

    private boolean clarificationCase(AnswerRecord record) {
        return "CLARIFY".equalsIgnoreCase(record.expectedMode);
    }

    private List<String> requiredTools(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        List<String> tools = new ArrayList<>();
        if (containsAny(normalized, "\u4f18\u60e0", "\u4ee3\u91d1\u5238", "voucher", "coupon")) {
            tools.add("voucherQuery");
        }
        if (containsAny(normalized, "\u63a2\u5e97", "\u7b14\u8bb0", "blog", "review")) {
            tools.add("blogSearch");
        }
        if (containsAny(normalized, "\u5730\u5740", "\u8425\u4e1a", "\u4eba\u5747", "\u8bc4\u5206",
                "\u5173\u95e8", "\u5f00\u5230", "\u6253\u70ca", "opening hours", "address", "rating")) {
            tools.add("shopDetail");
        }
        if (containsAny(normalized, "优惠", "代金券", "voucher", "coupon")) {
            tools.add("voucherQuery");
        }
        if (containsAny(normalized, "探店", "笔记", "blog", "review")) {
            tools.add("blogSearch");
        }
        if (containsAny(normalized, "地址", "营业", "人均", "评分", "opening hours", "address", "rating")) {
            tools.add("shopDetail");
        }
        return tools.stream().distinct().collect(Collectors.toList());
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private List<AiPromptMessage> parseHistory(String historyJson) throws IOException {
        if (isBlank(historyJson)) {
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
            if (!isBlank(role) && !isBlank(content)) {
                history.add(new AiPromptMessage(role, content));
            }
        }
        return history;
    }

    private void writeCsv(Path outputPath, List<AnswerRecord> records) throws IOException {
        List<String> output = new ArrayList<>();
        output.add("case_id,scenario,question,expected_mode,expected_shop_names,required_terms,"
                + "actual_mode,retrieval_queries,rewrite_model_called,valid_rewrite_output,outcome,"
                + "request_id,trace_id,retrieved_shop_ids,retrieved_evidence,tool_names,tool_evidence,"
                + "expected_entity_mentioned,all_expected_entities_mentioned,required_tools,"
                + "required_tools_invoked,unexpected_known_shop_names,automatic_pass,retrieval_ms,tool_ms,"
                + "first_token_ms,total_ms,error,manual_review,review_status,review_faithfulness,"
                + "review_constraints,review_coverage,review_realtime_consistency,review_no_evidence_safety,"
                + "review_failure_stage,review_notes,final_answer");
        for (AnswerRecord record : records) {
            output.add(toCsvLine(Arrays.asList(
                    record.caseId, record.scenario, record.question, record.expectedMode,
                    String.join("|", record.expectedShopNames), String.join("|", record.requiredTerms),
                    record.actualMode, String.join(" || ", record.retrievalQueries),
                    String.valueOf(record.rewriteModelCalled), String.valueOf(record.validRewriteOutput),
                    record.outcome, record.requestId, record.traceId,
                    joinLongs(record.retrievedShopIds), record.retrievedEvidence,
                    String.join("|", record.toolNames), record.toolEvidence,
                    String.valueOf(record.expectedEntityMentioned),
                    String.valueOf(record.allExpectedEntitiesMentioned),
                    String.join("|", record.requiredTools), String.valueOf(record.requiredToolsInvoked),
                    String.join("|", record.unexpectedKnownShopNames), String.valueOf(record.automaticPass),
                    String.valueOf(record.retrievalMs), String.valueOf(record.toolMs),
                    String.valueOf(record.firstTokenMs), String.valueOf(record.totalMs),
                    record.error, record.manualReview,
                    "PENDING", "", "", "", "", "", "", "", record.answer
            )));
        }
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.write(outputPath, output, StandardCharsets.UTF_8);
    }

    private void writeReport(Path reportPath, Path sourcePath, String sourceSplit,
                             List<AnswerRecord> records) throws IOException {
        List<AnswerRecord> retrievalCases = records.stream()
                .filter(record -> !clarificationCase(record))
                .collect(Collectors.toList());
        List<AnswerRecord> clarificationCases = records.stream()
                .filter(this::clarificationCase)
                .collect(Collectors.toList());
        List<AnswerRecord> multiIntentCases = retrievalCases.stream()
                .filter(record -> "multi-intent".equalsIgnoreCase(record.scenario))
                .collect(Collectors.toList());
        List<AnswerRecord> toolRequiredCases = retrievalCases.stream()
                .filter(record -> !record.requiredTools.isEmpty())
                .collect(Collectors.toList());
        StringBuilder report = new StringBuilder();
        report.append("# \u7aef\u5230\u7aef RAG \u56de\u7b54\u8bc4\u6d4b\n\n");
        report.append("> \u751f\u6210\u65f6\u95f4\uff1a").append(LocalDateTime.now()).append('\n');
        report.append("> \u8bc4\u6d4b\u6e90\uff1a`").append(sourcePath).append("` / split=`")
                .append(sourceSplit).append("`\uff0c\u6bcf\u6761\u6848\u4f8b\u72ec\u7acb\u8fd0\u884c\u751f\u4ea7 RAG \u94fe\u8def\u3002\n\n");
        report.append("## \u7ed3\u679c\n\n");
        report.append("| \u6307\u6807 | \u7ed3\u679c | \u8bf4\u660e |\n|---|---:|---|\n");
        report.append("|\u8fd0\u884c\u6210\u529f\u7387|").append(rate(records, record -> isBlank(record.error))).append("|\u94fe\u8def\u672a\u629b\u51fa\u6a21\u578b\u6216\u4f9d\u8d56\u5f02\u5e38|\n");
        report.append("|\u9884\u671f\u5b9e\u4f53\u547d\u4e2d\u7387|").append(rate(retrievalCases, record -> record.expectedEntityMentioned)).append("|\u6700\u7ec8\u56de\u7b54\u63d0\u53ca\u9884\u671f\u5e97\u94fa|\n");
        report.append("|\u591a\u610f\u56fe\u5b9e\u4f53\u8986\u76d6\u7387|").append(rate(multiIntentCases, record -> record.allExpectedEntitiesMentioned)).append("|\u4ec5\u7edf\u8ba1 multi-intent \u6848\u4f8b|\n");
        report.append("|\u5b9e\u65f6\u5de5\u5177\u8c03\u7528\u5339\u914d\u7387|").append(rate(toolRequiredCases, record -> record.requiredToolsInvoked)).append("|\u4ec5\u7edf\u8ba1\u9700\u8981\u5de5\u5177\u4e14\u4e0d\u5e94\u6f84\u6e05\u7684\u6848\u4f8b|\n");
        report.append("|\u6b67\u4e49\u6f84\u6e05\u7387|").append(rate(clarificationCases, record -> record.clarificationCorrect)).append("|\u65e0\u6cd5\u552f\u4e00\u89e3\u6790\u7684\u95ee\u9898\u662f\u5426\u4e3b\u52a8\u6f84\u6e05|\n");
        report.append("|\u7ed3\u6784\u5316\u81ea\u52a8\u901a\u8fc7\u7387|").append(rate(records, record -> record.automaticPass)).append("|\u4e0d\u7b49\u540c\u4e8e\u6700\u7ec8\u7b54\u6848\u4e8b\u5b9e\u6b63\u786e\u7387|\n");
        report.append("|P50 / P95 \u603b\u8017\u65f6|").append(percentile(records, record -> record.totalMs, 0.50D))
                .append("ms / ").append(percentile(records, record -> record.totalMs, 0.95D)).append("ms|\u542b\u91cd\u5199\u3001\u68c0\u7d22\u3001\u5de5\u5177\u548c\u6700\u7ec8\u6a21\u578b|\n");
        report.append("|P50 / P95 \u9996 Token|").append(percentile(records, record -> record.firstTokenMs, 0.50D))
                .append("ms / ").append(percentile(records, record -> record.firstTokenMs, 0.95D)).append("ms|\u8bc4\u6d4b\u8fd0\u884c\u5668\u4f7f\u7528\u6d41\u5f0f\u6a21\u578b\u5ba2\u6237\u7aef\u91c7\u96c6\u9996\u6bb5\u8f93\u51fa|\n\n");
        report.append("|\u91cd\u5199\u6a21\u578b\u6709\u6548 JSON \u6bd4\u4f8b|")
                .append(rate(records, record -> !record.rewriteModelCalled || record.validRewriteOutput))
                .append("|\u672a\u89e6\u53d1\u6a21\u578b\u7684\u5feb\u901f\u8def\u5f84\u4e0d\u8ba1\u4e3a\u5931\u8d25|\n");
        report.append("## \u5224\u5b9a\u8fb9\u754c\n\n");
        report.append("\u81ea\u52a8\u8bc4\u6d4b\u53ef\u68c0\u67e5\u5b9e\u4f53\u8986\u76d6\u3001\u6b67\u4e49\u6f84\u6e05\u3001\u5de5\u5177\u9009\u62e9\u3001\u672a\u9884\u671f\u5e97\u94fa\u5f15\u7528\u548c Trace \u5b8c\u6574\u6027\u3002")
                .append("\u4f18\u60e0\u5238\u6587\u6848\u3001\u4ef7\u683c\u3001\u5730\u5740\u3001\u8425\u4e1a\u65f6\u95f4\u7b49\u7ec6\u7c92\u5ea6\u4e8b\u5b9e\u4ecd\u9700\u6839\u636e CSV \u4e2d\u7684\u6700\u7ec8\u56de\u7b54\u4e0e\u5de5\u5177\u8bc1\u636e\u8fdb\u884c\u4eba\u5de5\u590d\u6838\uff0c\u4e0d\u5c06\u7ed3\u6784\u5316\u901a\u8fc7\u7387\u5ba3\u79f0\u4e3a\u7b54\u6848\u51c6\u786e\u7387\u3002\n\n");
        report.append("CSV \u5df2\u9884\u7559 `review_*` \u5b57\u6bb5\uff0c\u4eba\u5de5\u590d\u6838\u9700\u5206\u522b\u5224\u5b9a\u4e8b\u5b9e\u5fe0\u5b9e\u5ea6\u3001\u7ea6\u675f\u9075\u5b88\u3001\u591a\u95ee\u9898\u8986\u76d6\u3001\u5b9e\u65f6\u6570\u636e\u4e00\u81f4\u6027\u548c\u65e0\u8bc1\u636e\u5b89\u5168\u6027\uff0c\u5e76\u6807\u6ce8\u5931\u8d25\u5f52\u56e0\u3002\n\n");
        report.append("## \u5931\u8d25\u6848\u4f8b\n\n");
        report.append("|\u6848\u4f8b|\u573a\u666f|\u95ee\u9898|\u6a21\u5f0f / \u7ed3\u679c|\u539f\u56e0|Trace|\n|---|---|---|---|---|---|\n");
        for (AnswerRecord record : records) {
            if (record.automaticPass) {
                continue;
            }
            report.append('|').append(record.caseId).append('|').append(record.scenario).append('|')
                    .append(escapeMarkdown(record.question)).append('|')
                    .append(record.actualMode).append(" / ").append(record.outcome).append('|')
                    .append(escapeMarkdown(failureReason(record))).append('|')
                    .append(record.traceId == null ? "" : '`' + record.traceId + '`').append("|\n");
        }
        Files.createDirectories(reportPath.toAbsolutePath().getParent());
        Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String failureReason(AnswerRecord record) {
        if (!isBlank(record.error)) {
            return "runtime error: " + record.error;
        }
        if (record.rewriteModelCalled && !record.validRewriteOutput) {
            return "query rewrite returned invalid output and the deterministic fallback was used";
        }
        if (!record.clarificationCorrect) {
            return "clarification mode mismatch";
        }
        if (!record.expectedEntityMentioned || !record.allExpectedEntitiesMentioned) {
            return "expected store entity missing from final answer";
        }
        if (!record.requiredToolsInvoked) {
            return "expected live tool was not invoked: " + record.requiredTools;
        }
        if (!record.unexpectedKnownShopNames.isEmpty()) {
            return "answer mentioned a store outside retrieved evidence: " + record.unexpectedKnownShopNames;
        }
        return "outcome mismatch";
    }

    private String rate(List<AnswerRecord> records, RecordPredicate predicate) {
        if (records.isEmpty()) {
            return "N/A";
        }
        long hit = records.stream().filter(predicate::test).count();
        return String.format(java.util.Locale.ROOT, "%.2f%% (%d/%d)", hit * 100D / records.size(), hit, records.size());
    }

    private long percentile(List<AnswerRecord> records, LongValue value, double percentile) {
        List<Long> values = records.stream().map(value::get).filter(current -> current >= 0L)
                .sorted().collect(Collectors.toList());
        if (values.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0, (int) Math.ceil(values.size() * percentile) - 1);
        return values.get(index);
    }

    private boolean namesMatch(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        return normalizedLeft.equals(normalizedRight)
                || normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft);
    }

    private void requireColumns(Map<String, Integer> indexes, String... columns) {
        for (String column : columns) {
            if (!indexes.containsKey(column)) {
                throw new IllegalStateException("Missing evaluation source column: " + column);
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

    private List<String> splitPipe(String value) {
        if (isBlank(value)) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split("\\|"))
                .map(String::trim).filter(part -> !part.isEmpty()).collect(Collectors.toList());
    }

    private String joinLongs(List<Long> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining("|"));
    }

    private String limitText(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[\\s\\p{P}\\p{S}]", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String escapeMarkdown(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace('\n', ' ');
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

    private interface RecordPredicate { boolean test(AnswerRecord record); }
    private interface LongValue { long get(AnswerRecord record); }

    private static class AnswerRecord {
        private String caseId;
        private String scenario;
        private String question;
        private String expectedMode;
        private List<String> expectedShopNames = Collections.emptyList();
        private List<String> requiredTerms = Collections.emptyList();
        private List<AiPromptMessage> history = Collections.emptyList();
        private String actualMode;
        private List<String> retrievalQueries = Collections.emptyList();
        private boolean rewriteModelCalled;
        private boolean validRewriteOutput;
        private String outcome;
        private String requestId;
        private String traceId;
        private List<Long> retrievedShopIds = Collections.emptyList();
        private String retrievedEvidence;
        private List<String> toolNames = Collections.emptyList();
        private String toolEvidence;
        private List<String> requiredTools = Collections.emptyList();
        private String answer;
        private boolean expectedEntityMentioned;
        private boolean allExpectedEntitiesMentioned;
        private boolean requiredToolsInvoked;
        private boolean clarificationCorrect;
        private List<String> unexpectedKnownShopNames = Collections.emptyList();
        private boolean automaticPass;
        private long retrievalMs;
        private long toolMs;
        private long firstTokenMs;
        private long totalMs;
        private String error;
        private String manualReview;
    }
}
