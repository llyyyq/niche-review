package com.hmdp.evidence;

import com.hmdp.ai.ShopKnowledge;
import com.hmdp.config.AiEvaluationProperties;
import com.hmdp.service.IShopKnowledgeService;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs only when AI_EVALUATION_ENABLED=true and updates the checked-in RAG
 * evidence file with live retrieval results from the current knowledge base.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.evaluation", name = "enabled", havingValue = "true")
public class RagEvaluationRunner implements ApplicationRunner {

    private static final String QUESTION = "question";
    private static final String EXPECTED_SHOP_IDS = "expected_shop_ids";
    private static final String VECTOR_TOP3 = "vector_top3";
    private static final String HYBRID_TOP3 = "hybrid_top3";
    private static final String VECTOR_HIT_AT_1 = "vector_hit_at_1";
    private static final String VECTOR_HIT_AT_3 = "vector_hit_at_3";
    private static final String HYBRID_HIT_AT_1 = "hybrid_hit_at_1";
    private static final String HYBRID_HIT_AT_3 = "hybrid_hit_at_3";

    @Resource
    private IShopKnowledgeService shopKnowledgeService;

    @Resource
    private AiEvaluationProperties evaluationProperties;

    @Override
    public void run(ApplicationArguments args) {
        Path path = Paths.get(evaluationProperties.getRagCasesPath());
        if (!Files.exists(path)) {
            log.warn("RAG evaluation skipped because the case file does not exist: {}", path.toAbsolutePath());
            return;
        }
        try {
            evaluate(path);
        } catch (Exception e) {
            log.error("RAG evaluation failed. The case file was left unchanged: {}", path.toAbsolutePath(), e);
        }
    }

    private void evaluate(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("RAG evaluation case file is empty");
        }
        List<String> headers = parseCsvLine(lines.get(0));
        Map<String, Integer> indexes = headerIndexes(headers);
        validateHeaders(indexes);

        List<String> output = new ArrayList<>();
        output.add(toCsvLine(headers));
        int scoredCases = 0;
        int vectorHitAt1 = 0;
        int vectorHitAt3 = 0;
        int hybridHitAt1 = 0;
        int hybridHitAt3 = 0;

        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            if (line.trim().isEmpty()) {
                continue;
            }
            List<String> row = new ArrayList<>(parseCsvLine(line));
            while (row.size() < headers.size()) {
                row.add("");
            }
            String question = value(row, indexes, QUESTION);
            if (question.trim().isEmpty()) {
                output.add(toCsvLine(row));
                continue;
            }

            List<Long> vectorResults = shopIds(shopKnowledgeService.searchRelevantShops(question, false));
            List<Long> hybridResults = shopIds(shopKnowledgeService.searchRelevantShops(question, true));
            List<Long> expectedIds = parseIds(value(row, indexes, EXPECTED_SHOP_IDS));
            String vectorFirstHit = "";
            String vectorTop3Hit = "";
            String hybridFirstHit = "";
            String hybridTop3Hit = "";
            if (!expectedIds.isEmpty()) {
                scoredCases++;
                boolean currentVectorHitAt1 = containsFirst(vectorResults, expectedIds);
                boolean currentVectorHitAt3 = containsAny(vectorResults, expectedIds);
                boolean currentHybridHitAt1 = containsFirst(hybridResults, expectedIds);
                boolean currentHybridHitAt3 = containsAny(hybridResults, expectedIds);
                vectorFirstHit = String.valueOf(currentVectorHitAt1);
                vectorTop3Hit = String.valueOf(currentVectorHitAt3);
                hybridFirstHit = String.valueOf(currentHybridHitAt1);
                hybridTop3Hit = String.valueOf(currentHybridHitAt3);
                if (currentVectorHitAt1) {
                    vectorHitAt1++;
                }
                if (currentVectorHitAt3) {
                    vectorHitAt3++;
                }
                if (currentHybridHitAt1) {
                    hybridHitAt1++;
                }
                if (currentHybridHitAt3) {
                    hybridHitAt3++;
                }
            }

            setValue(row, indexes, VECTOR_TOP3, joinIds(vectorResults));
            setValue(row, indexes, HYBRID_TOP3, joinIds(hybridResults));
            setValue(row, indexes, VECTOR_HIT_AT_1, vectorFirstHit);
            setValue(row, indexes, VECTOR_HIT_AT_3, vectorTop3Hit);
            setValue(row, indexes, HYBRID_HIT_AT_1, hybridFirstHit);
            setValue(row, indexes, HYBRID_HIT_AT_3, hybridTop3Hit);
            output.add(toCsvLine(row));
        }
        Files.write(path, output, StandardCharsets.UTF_8);
        log.info("RAG evaluation completed, scoredCases={}, vectorHitAt1={}/{}, vectorHitAt3={}/{}, "
                        + "hybridHitAt1={}/{}, hybridHitAt3={}/{}",
                scoredCases, vectorHitAt1, scoredCases, vectorHitAt3, scoredCases,
                hybridHitAt1, scoredCases, hybridHitAt3, scoredCases);
    }

    private Map<String, Integer> headerIndexes(List<String> headers) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            indexes.put(headers.get(index), index);
        }
        return indexes;
    }

    private void validateHeaders(Map<String, Integer> indexes) {
        for (String required : Arrays.asList(QUESTION, EXPECTED_SHOP_IDS, VECTOR_TOP3, HYBRID_TOP3,
                VECTOR_HIT_AT_1, VECTOR_HIT_AT_3, HYBRID_HIT_AT_1, HYBRID_HIT_AT_3)) {
            if (!indexes.containsKey(required)) {
                throw new IllegalStateException("Missing RAG evaluation column: " + required);
            }
        }
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
        row.set(index, value);
    }

    private List<Long> shopIds(List<ShopKnowledge> knowledgeList) {
        if (knowledgeList == null) {
            return Collections.emptyList();
        }
        return knowledgeList.stream()
                .map(ShopKnowledge::getShopId)
                .filter(id -> id != null)
                .collect(Collectors.toList());
    }

    private List<Long> parseIds(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : value.split("\\|")) {
            if (!part.trim().isEmpty()) {
                ids.add(Long.valueOf(part.trim()));
            }
        }
        return ids;
    }

    private boolean containsAny(List<Long> actualIds, List<Long> expectedIds) {
        for (Long actualId : actualIds) {
            if (expectedIds.contains(actualId)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsFirst(List<Long> actualIds, List<Long> expectedIds) {
        return !actualIds.isEmpty() && expectedIds.contains(actualIds.get(0));
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
        String safeValue = value == null ? "" : value;
        if (safeValue.contains(",") || safeValue.contains("\"") || safeValue.contains("\n")) {
            return "\"" + safeValue.replace("\"", "\"\"") + "\"";
        }
        return safeValue;
    }
}
