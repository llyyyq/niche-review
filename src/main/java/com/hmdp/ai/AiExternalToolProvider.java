package com.hmdp.ai;

import java.util.List;

/**
 * Extension point for remote tools. A future MCP Client adapter can implement this interface
 * and expose discovered MCP tools without changing the agent loop.
 */
public interface AiExternalToolProvider {

    String getProviderName();

    List<String> supportedToolNames();

    AiToolExecution execute(AiToolInvocation invocation) throws Exception;
}
