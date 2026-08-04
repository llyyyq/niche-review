# 端到端 RAG 回答评测

> 生成时间：2026-08-03T20:40:07.397
> 评测源：`docs\project-proof\query-rewrite-answer-holdout.csv` / split=`HOLDOUT`，每条案例独立运行生产 RAG 链路。

## 结果

| 指标 | 结果 | 说明 |
|---|---:|---|
|运行成功率|100.00% (40/40)|链路未抛出模型或依赖异常|
|预期实体命中率|61.11% (22/36)|最终回答提及预期店铺|
|多意图实体覆盖率|75.00% (6/8)|仅统计 multi-intent 案例|
|实时工具调用匹配率|67.65% (23/34)|仅统计需要工具且不应澄清的案例|
|歧义澄清率|50.00% (2/4)|无法唯一解析的问题是否主动澄清|
|结构化自动通过率|57.50% (23/40)|不等同于最终答案事实正确率|
|P50 / P95 总耗时|3334ms / 14091ms|含重写、检索、工具和最终模型|
|P50 / P95 首 Token|3149ms / 11901ms|评测运行器使用流式模型客户端采集首段输出|

|重写模型有效 JSON 比例|77.50% (31/40)|未触发模型的快速路径不计为失败|
## 判定边界

自动评测可检查实体覆盖、歧义澄清、工具选择、未预期店铺引用和 Trace 完整性。优惠券文案、价格、地址、营业时间等细粒度事实仍需根据 CSV 中的最终回答与工具证据进行人工复核，不将结构化通过率宣称为答案准确率。

CSV 已预留 `review_*` 字段，人工复核需分别判定事实忠实度、约束遵守、多问题覆盖、实时数据一致性和无证据安全性，并标注失败归因。

## 失败案例

|案例|场景|问题|模式 / 结果|原因|Trace|
|---|---|---|---|---|---|
|HO007|pass-through|Mamala西餐厅有什么特色？|PASS_THROUGH / ANSWERED|expected store entity missing from final answer|`4bef92b7a98be5bfc40b37f311024d0b`|
|HO009|context-reference|那家店开到几点？|CLARIFY / CLARIFIED|query rewrite returned invalid output and the deterministic fallback was used|`768fb596953c450cce4ded2b1f0e1fb5`|
|HO011|context-reference|第二家的人均和地址分别是什么？|CLARIFY / CLARIFIED|expected store entity missing from final answer|`1f17a5b3e306be16c9ba6f731c10ab24`|
|HO013|context-reference|那里有公开探店笔记吗？|CLARIFY / CLARIFIED|query rewrite returned invalid output and the deterministic fallback was used|`abbe935e37fab4ccbe78ea3b418fa914`|
|HO015|context-reference|后一家有没有优惠券？|CLARIFY / CLARIFIED|expected store entity missing from final answer|`7101f848b579b7768eb5e1991f6d5da2`|
|HO016|context-reference|它在哪里，周末营业吗？|CLARIFY / CLARIFIED|query rewrite returned invalid output and the deterministic fallback was used|`6beee120b558622201e93c65d6aeb2bb`|
|HO018|long-noisy|我不需要泛泛的美食榜单，也不用重复解释什么是本地生活。现在真正需要解决的是：下班后想和同事去唱歌，最好离运河上街不远，营业时间尽量晚一些，预算不要太高。请从已有店铺里找合适的KTV，并明确给出地址、评分和关门时间；餐厅、足道和其他类别都不是这次要找的内容。|PASS_THROUGH / NO_EVIDENCE|expected store entity missing from final answer|`0d165b203e756bd7f5cf3967b2463695`|
|HO019|long-noisy|家里人让我找一家可以多人一起吃、不会太贵的餐厅，我之前还问过电影和出行，这些都和本次问题无关。请只根据现有店铺信息，找人均不超过90元、评分尽量高、适合朋友一起吃饭的餐厅；回答时说明店名、人均和评分，不要把没有证据的优惠活动写进去。|PASS_THROUGH / NO_EVIDENCE|expected store entity missing from final answer|`36346f302e2bb6160ff7bc4d4e9129f4`|
|HO021|long-noisy|我们正在比较两家寿司店以外的选择，聊天里提到的购物和旅游并不重要。请你只查浅草屋寿司的公开探店笔记，告诉我是否存在、笔记主要描述了哪些体验；不要把其他餐厅的博客当成它的内容，也不要因为没有足够资料就编造菜品或评分。|CLARIFY / CLARIFIED|expected store entity missing from final answer|`0e363aad2812fa58304b868670058a3f`|
|HO024|long-noisy|这次请只回答炉鱼的实时和公开资料：我想知道它适不适合三个人晚餐，人均是否不超过100元、地址在哪里、有没有公开探店笔记。请将事实和推测分开表达；不要因为问题较长就忽略其中任何一个条件，也不要将其他店铺的博客或优惠券套到炉鱼上。|CLARIFY / CLARIFIED|expected store entity missing from final answer|`46b4cd06840e3755b3a38ae84289e783`|
|HO030|multi-intent|推荐适合情侣约会的西餐厅，并顺便查询Mamala西餐厅现在的地址和营业时间。|FALLBACK / ANSWERED|query rewrite returned invalid output and the deterministic fallback was used|`07bd5212053dc512a42595200f59f636`|
|HO031|multi-intent|查询新白鹿餐厅人均和地址，同时推荐一家评分高于4分、预算100元以内的餐厅。|DECOMPOSE / ANSWERED|expected store entity missing from final answer|`92736601b72b59e080be80dd8685394e`|
|HO033|inherited-constraint|第二家是否适合四个人聚餐？|CLARIFY / CLARIFIED|expected store entity missing from final answer|`ffbc3be17218857b61f29136eb9c8d60`|
|HO034|inherited-constraint|那家店几点关门，预算不高的话合适吗？|CLARIFY / CLARIFIED|query rewrite returned invalid output and the deterministic fallback was used|`6d63855f0255bfce3145ee557185341d`|
|HO036|inherited-constraint|那里有公开探店笔记吗，三个人去是否合适？|CLARIFY / CLARIFIED|query rewrite returned invalid output and the deterministic fallback was used|`60fe3656daf8b2753dfef8cedd6b184b`|
|HO037|ambiguous|它有什么优惠？|REWRITE / ANSWERED|clarification mode mismatch|`c77d97ca2e157d2068b171ce126a1b17`|
|HO040|ambiguous|另外一家有什么优惠？|PASS_THROUGH / ANSWERED|clarification mode mismatch|`68c5ab3c40b4aff705e6b70603091ef3`|
