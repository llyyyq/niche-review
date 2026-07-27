package com.hmdp.ai;

import java.util.Collections;
import java.util.List;

public class AiToolPlan {

    private final boolean finished;
    private final List<String> toolNames;

    public AiToolPlan(boolean finished, List<String> toolNames) {
        this.finished = finished;
        this.toolNames = toolNames == null ? Collections.emptyList() : toolNames;
    }

    public boolean isFinished() {
        return finished;
    }

    public List<String> getToolNames() {
        return toolNames;
    }
}
