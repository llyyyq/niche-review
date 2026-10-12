package com.hmdp.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validated, model-independent representation of one user query.
 */
public final class AiQueryPlan {

    private final AiQueryPlanStatus status;
    private final List<AiQueryTask> tasks;
    private final String clarification;
    private final boolean modelCalled;
    private final boolean validModelOutput;
    private final long planMs;
    private final int originalChars;
    private final int plannedChars;
    private final String failureReason;

    public AiQueryPlan(AiQueryPlanStatus status, List<AiQueryTask> tasks, String clarification,
                       boolean modelCalled, boolean validModelOutput, long planMs,
                       int originalChars, int plannedChars, String failureReason) {
        this.status = status;
        this.tasks = tasks == null
                ? Collections.<AiQueryTask>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tasks));
        this.clarification = clarification;
        this.modelCalled = modelCalled;
        this.validModelOutput = validModelOutput;
        this.planMs = planMs;
        this.originalChars = originalChars;
        this.plannedChars = plannedChars;
        this.failureReason = failureReason;
    }

    public AiQueryPlanStatus getStatus() {
        return status;
    }

    public List<AiQueryTask> getTasks() {
        return tasks;
    }

    public String getClarification() {
        return clarification;
    }

    public boolean isModelCalled() {
        return modelCalled;
    }

    public boolean isValidModelOutput() {
        return validModelOutput;
    }

    public long getPlanMs() {
        return planMs;
    }

    public int getOriginalChars() {
        return originalChars;
    }

    public int getPlannedChars() {
        return plannedChars;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public boolean isReady() {
        return status == AiQueryPlanStatus.READY && !tasks.isEmpty();
    }

    public boolean requiresClarification() {
        return status == AiQueryPlanStatus.CLARIFY || status == AiQueryPlanStatus.FAILED;
    }
}
