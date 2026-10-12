package com.hmdp.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Retrieval evidence for one resolved business task.
 *
 * Keeping the task beside its candidates prevents multi-task requests from
 * accidentally sharing filters, tool arguments, or prompt evidence.
 */
public final class AiTaskRetrievalResult {

    private final int taskIndex;
    private final AiResolvedQueryTask task;
    private final List<ShopKnowledge> candidates;
    private final boolean semanticRequested;
    private final boolean semanticDegraded;

    public AiTaskRetrievalResult(int taskIndex, AiResolvedQueryTask task,
                                 List<ShopKnowledge> candidates) {
        this(taskIndex, task, candidates, false, false);
    }

    public AiTaskRetrievalResult(int taskIndex, AiResolvedQueryTask task,
                                 List<ShopKnowledge> candidates,
                                 boolean semanticRequested,
                                 boolean semanticDegraded) {
        this.taskIndex = taskIndex;
        this.task = task;
        this.candidates = candidates == null
                ? Collections.<ShopKnowledge>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(candidates));
        this.semanticRequested = semanticRequested;
        this.semanticDegraded = semanticDegraded;
    }

    public int getTaskIndex() {
        return taskIndex;
    }

    public AiResolvedQueryTask getTask() {
        return task;
    }

    public List<ShopKnowledge> getCandidates() {
        return candidates;
    }

    public boolean isSemanticRequested() {
        return semanticRequested;
    }

    public boolean isSemanticDegraded() {
        return semanticDegraded;
    }
}
