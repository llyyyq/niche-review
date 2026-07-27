package com.hmdp.ai;

public class AiToolExecution {

    private final String toolName;
    private final String resultContent;

    public AiToolExecution(String toolName, String resultContent) {
        this.toolName = toolName;
        this.resultContent = resultContent;
    }

    public String getToolName() {
        return toolName;
    }

    public String getResultContent() {
        return resultContent;
    }
}
