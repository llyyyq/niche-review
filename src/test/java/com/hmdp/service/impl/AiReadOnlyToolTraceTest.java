package com.hmdp.service.impl;

import com.hmdp.ai.AiTraceContext;
import com.hmdp.ai.AiTraceSpanScope;
import com.hmdp.ai.AiToolExecution;
import com.hmdp.ai.ShopKnowledge;
import com.hmdp.entity.AiToolLog;
import com.hmdp.service.IAiToolLogService;
import com.hmdp.service.IAiTraceService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiReadOnlyToolTraceTest {

    @Mock
    private IShopService shopService;

    @Mock
    private IVoucherService voucherService;

    @Mock
    private IBlogService blogService;

    @Mock
    private IAiToolLogService toolLogService;

    @Mock
    private IAiTraceService traceService;

    private AiReadOnlyToolServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiReadOnlyToolServiceImpl();
        ReflectionTestUtils.setField(service, "shopService", shopService);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "aiToolLogService", toolLogService);
        ReflectionTestUtils.setField(service, "aiTraceService", traceService);
    }

    @Test
    void failedToolKeepsTraceCorrelationAndIsIsolatedFromAgentFlow() {
        AiTraceContext root = new AiTraceContext(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "1111111111111111",
                "1111111111111111",
                null,
                10L,
                20L,
                30L,
                40L
        );
        when(traceService.startSpan(eq(root), eq("TOOL_CALL"), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> attributes = invocation.getArgument(2);
                    return new AiTraceSpanScope(
                            traceService,
                            root.child("2222222222222222"),
                            attributes
                    );
                });
        when(shopService.listByIds(anyList())).thenThrow(new IllegalStateException("database unavailable"));
        ShopKnowledge knowledge = new ShopKnowledge(
                1L, "shop", 0.9D, Collections.<String, Object>emptyMap());

        List<AiToolExecution> executions = service.executeTools(
                root,
                10L,
                20L,
                Collections.singletonList("shopDetail"),
                null,
                null,
                Collections.singletonList(knowledge)
        );

        assertTrue(executions.isEmpty());
        verify(traceService).completeSpan(
                argThat(context -> "2222222222222222".equals(context.getCurrentSpanId())),
                eq(false),
                argThat(attributes -> "shopDetail".equals(attributes.get("toolName"))
                        && attributes.get("toolCallId") != null),
                any(IllegalStateException.class),
                anyLong()
        );
        verify(toolLogService).save(argThat((AiToolLog toolLog) ->
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".equals(toolLog.getTraceId())
                        && "2222222222222222".equals(toolLog.getSpanId())
                        && toolLog.getToolCallId() != null
                        && toolLog.getAssistantMessageId().equals(40L)
                        && toolLog.getSuccess().equals(0)));
    }

    @Test
    void openingHoursSynonymsShouldUseDirectShopDetailRouting() {
        assertTrue(service.shouldUseDirectToolRouting("\u6d77\u5e95\u635e\u706b\u9505\u8425\u4e1a\u5230\u51e0\u70b9\uff1f", null, null));
        assertTrue(service.shouldUseDirectToolRouting("\u5f00\u4e50\u8feaKTV\u51e0\u70b9\u5173\u95e8\uff1f", null, null));
        assertTrue(service.shouldUseDirectToolRouting("INLOVE KTV\u5f00\u5230\u51e0\u70b9\uff1f", null, null));
        assertTrue(service.shouldUseDirectToolRouting("\u661f\u805a\u4f1aKTV\u4ec0\u4e48\u65f6\u5019\u6253\u70ca\uff1f", null, null));
    }
}
