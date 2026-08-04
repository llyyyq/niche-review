# 端到端 RAG 回答评测

> 生成时间：2026-08-03T19:42:20.121
> 评测口径：复用 `query-rewrite-cases.csv` 的 40 条 TEST 案例，每条案例独立运行生产 RAG 链路。

## 结果

| 指标 | 结果 | 说明 |
|---|---:|---|
|运行成功率|100.00% (40/40)|链路未抛出模型或依赖异常|
|预期实体命中率|85.71% (30/35)|最终回答提及预期店铺|
|多意图实体覆盖率|97.14% (34/35)|多问题不遗漏预期店铺|
|实时工具调用匹配率|85.71% (30/35)|优惠券/探店/店铺事实等问题是否调用相应工具|
|歧义澄清率|100.00% (5/5)|无法唯一解析的问题是否主动澄清|
|结构化自动通过率|82.50% (33/40)|不等同于最终答案事实正确率|
|P50 / P95 总耗时|4527ms / 11434ms|含重写、检索、工具和最终模型|
|P50 / P95 首 Token|4349ms / 10029ms|评测运行器使用流式模型客户端采集首段输出|

## 判定边界

自动评测可检查实体覆盖、歧义澄清、工具选择、未预期店铺引用和 Trace 完整性。优惠券文案、价格、地址、营业时间等细粒度事实仍需根据 CSV 中的最终回答与工具证据进行人工复核，不将结构化通过率宣称为答案准确率。

## 失败案例

|案例|场景|问题|模式 / 结果|原因|Trace|
|---|---|---|---|---|---|
|QR005|pass-through|海底捞火锅营业到几点？|PASS_THROUGH / ANSWERED|expected live tool was not invoked: [shopDetail]|`8eef5f0b1e90737d07affc1c5757513b`|
|QR014|context-reference|那家店几点关门？|CLARIFY / CLARIFIED|expected store entity missing from final answer|`d5a95ded20aadfabe888187dee727452`|
|QR016|context-reference|它还有优惠券吗？|CLARIFY / CLARIFIED|expected store entity missing from final answer|`97fcdda492797bf1ccd70efb90dc0619`|
|QR018|context-reference|那里营业到几点？|CLARIFY / CLARIFIED|expected store entity missing from final answer|`eb9686b0b11a6a08547d4d33a0827ac5`|
|QR019|context-reference|换一家评分更高的，它在哪里？|CLARIFY / CLARIFIED|expected store entity missing from final answer|`65308063f1f4015e7fa97168971a7874`|
|QR036|multi-intent|推荐运河上街人均100元以内的餐厅，同时查询开乐迪KTV几点关门。|FALLBACK / ANSWERED|expected store entity missing from final answer|`2b53247a3f0edbec27ee8b82320b0ee7`|
|QR044|inherited-constraint|第二家评分怎么样？|CLARIFY / CLARIFIED|expected store entity missing from final answer|`6b14b1de0bceffc5ff575523cf5ab1f6`|
