package com.hmdp.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Program-validated query plan consumed by retrieval and tool routing.
 */
public final class AiResolvedQueryPlan {

    private final AiQueryPlanStatus status;
    private final List<AiResolvedQueryTask> tasks;
    private final String clarification;
    private final AiQueryPlan sourcePlan;

    public AiResolvedQueryPlan(AiQueryPlanStatus status, List<AiResolvedQueryTask> tasks,
                               String clarification, AiQueryPlan sourcePlan) {
        this.status = status;
        this.tasks = tasks == null
                ? Collections.<AiResolvedQueryTask>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tasks));
        this.clarification = clarification;
        this.sourcePlan = sourcePlan;
    }

    public AiQueryPlanStatus getStatus() {
        return status;
    }

    public List<AiResolvedQueryTask> getTasks() {
        return tasks;
    }

    public String getClarification() {
        return clarification;
    }

    public AiQueryPlan getSourcePlan() {
        return sourcePlan;
    }

    public boolean isReady() {
        return status == AiQueryPlanStatus.READY && !tasks.isEmpty();
    }

    public boolean requiresClarification() {
        return status == AiQueryPlanStatus.CLARIFY || status == AiQueryPlanStatus.FAILED;
    }

    public List<Long> candidateShopIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (AiResolvedQueryTask task : tasks) {
            ids.addAll(task.getShopIds());
        }
        return new ArrayList<>(ids);
    }

    public List<String> requiredFacts() {
        Set<String> facts = new LinkedHashSet<>();
        for (AiResolvedQueryTask task : tasks) {
            facts.addAll(task.getRequiredFacts());
        }
        return new ArrayList<>(facts);
    }

    /** Resolved type IDs used by location-aware read-only tools. */
    public List<Long> candidateTypeIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (AiResolvedQueryTask task : tasks) {
            ids.addAll(task.getTypeIds());
        }
        return new ArrayList<>(ids);
    }
}
