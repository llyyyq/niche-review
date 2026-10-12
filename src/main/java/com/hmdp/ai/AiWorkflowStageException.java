package com.hmdp.ai;

/** Carries the failed workflow stage through the asynchronous chat boundary. */
public final class AiWorkflowStageException extends RuntimeException {

    private final String stage;

    public AiWorkflowStageException(String stage, Throwable cause) {
        super(cause == null ? null : cause.getMessage(), cause);
        this.stage = stage;
    }

    public String getStage() {
        return stage;
    }
}
