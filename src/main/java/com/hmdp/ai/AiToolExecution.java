package com.hmdp.ai;

public class AiToolExecution {

    public enum Status {
        SUCCESS,
        FAILED,
        /** Planned but not run, so its absence is not evidence that the data does not exist. */
        SKIPPED
    }

    private final String toolName;
    private final String resultContent;
    private final int taskIndex;
    private final Status status;

    public AiToolExecution(String toolName, String resultContent) {
        this(0, toolName, resultContent, true);
    }

    public AiToolExecution(int taskIndex, String toolName, String resultContent) {
        this(taskIndex, toolName, resultContent, true);
    }

    public AiToolExecution(String toolName, String resultContent, boolean successful) {
        this(0, toolName, resultContent, successful);
    }

    public AiToolExecution(int taskIndex, String toolName, String resultContent,
                           boolean successful) {
        this(taskIndex, toolName, resultContent, successful ? Status.SUCCESS : Status.FAILED);
    }

    private AiToolExecution(int taskIndex, String toolName, String resultContent, Status status) {
        this.taskIndex = taskIndex;
        this.toolName = toolName;
        this.resultContent = resultContent;
        this.status = status;
    }

    public static AiToolExecution skipped(String toolName, String reason) {
        return new AiToolExecution(0, toolName, reason, Status.SKIPPED);
    }

    public AiToolExecution withTaskIndex(int taskIndex) {
        return new AiToolExecution(taskIndex, toolName, resultContent, status);
    }

    public String getToolName() {
        return toolName;
    }

    public String getResultContent() {
        return resultContent;
    }

    public int getTaskIndex() {
        return taskIndex;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccessful() {
        return status == Status.SUCCESS;
    }

    public boolean isSkipped() {
        return status == Status.SKIPPED;
    }
}
