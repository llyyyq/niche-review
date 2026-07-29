package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.evaluation")
public class AiEvaluationProperties {

    private Boolean enabled = false;

    private String mode = "rag";

    private String ragCasesPath = "docs/project-proof/rag-cases.csv";

    private String queryRewriteCasesPath = "docs/project-proof/query-rewrite-cases.csv";

    private String queryRewriteReportPath = "docs/project-proof/query-rewrite-evaluation.md";
}
