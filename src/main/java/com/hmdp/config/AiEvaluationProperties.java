package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.evaluation")
public class AiEvaluationProperties {

    private Boolean enabled = false;

    /** Runs real persisted conversations through the public chat service. */
    private Boolean conversationEnabled = false;

    /** A real tb_user.id used to own isolated evaluation conversations. */
    private Long conversationUserId;

    private String conversationCasesPath = "docs/project-proof/rag-conversation-holdout.csv";

    private String conversationCasesSplit = "HOLDOUT";

    private Integer conversationExpectedCaseCount = 40;

    /** Optional comma-separated retry subset, for example CE009,CE017. */
    private String conversationCaseIds;

    private String conversationOutputPath = "docs/project-proof/rag-conversation-holdout-result.csv";

    private String conversationReportPath = "docs/project-proof/rag-conversation-holdout-report.md";

    private String conversationTitlePrefix = "[E2E-EVAL]";

    private Long conversationTurnTimeoutMs = 150000L;
}
