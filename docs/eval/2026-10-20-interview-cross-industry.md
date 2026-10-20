# 面试跨行业真实模型评测（2026-10-20）

使用用户在允许网络的环境执行的真实 DeepSeek FLASH 结果：`/workspace/docs-eval-local/interview-cross-industry-2026-10-20/live-box-2a285057-5b1b-49e0-a9f2-4b0ed12d43af/`，数字来自 `summary.md` / `summary.json`、岗位 JSON 和 `attempt-1.json` 至 `attempt-102.json`。本轮沙箱只做离线分析与本地验证，没有调用 live。本机之前两轮 socket 受限的 0/6 记录是环境失败，不能替代这次真实结果。

出题 **6/6** 正常，33道题的数量、难度和显式配比均符合；软件技术关键词标记 **0/33**（不能等同人工确认绝无泄漏）。逐题评分 **55/66** 成功、**11/66** 在一次修复后仍 BusinessException（`source=unavailable`）；总结 **11/12** 成功，护士差回答仅保留逐题汇总（`AGGREGATE_ONLY`）。整体完整交付 **2/6**，另 **4/6 PARTIAL**，不能称为全通过。全部六岗位的较好回答均分高于较差回答，均分仅基于各组有效评分题。

|岗位|状态|请求/实际题数|成功评分 好/差|技术标记|均分 好/差|出题ms|评分总ms 好/差|总结ms 好/差|15题降级去重|
|---|---|---|---|---|---|---|---|---|---|
|新媒体运营|DELIVERED|3/3|3/3、3/3|0|52/18|2299|12045/10422|3584/2992|15/15|
|护士|PARTIAL|4/4|3/4、3/4|0|58/11|2428|20361/16646|3543/5340（失败）|15/15|
|销售|DELIVERED|5/5|5/5、5/5|0|67/16|2394|24185/23245|6440/3250|15/15|
|财务|PARTIAL|6/6|6/6、5/6|0|66/11|2829|32420/28041|3996/3487|15/15|
|人力资源专员|PARTIAL|7/7|5/7、6/7|0|57/16|2449|43861/30917|9869/3479|15/15|
|幼儿园教师|PARTIAL|8/8|6/8、5/8|0|54/14|3127|48004/46674|4281/8543|15/15|

评分按题串行：66次逻辑评分（含修复/失败）的平均耗时 **5103ms/题**，中位数 **4203.5ms**，范围 **3111–10800ms**；55次成功评分平均 **4354ms/题**。评分累计336789ms，每组总耗时10422–48004ms。总结耗时2992–9869ms；这是已有逐题分数之后的模型总结，不含缺失题评分、数据库、HTTP或后台任务，不能据此宣布完整报告 <20s 达标。102次主网关订阅包括6次出题、80次评分尝试、16次总结尝试，比无修复的84次多18次；内部安全分类请求不计入，可能另收费。

类型为 BEHAVIORAL、SITUATIONAL、KNOWLEDGE、CASE，综合仅为侧重点。六岗位关键词相关命中分别3/3、4/4、5/5、5/6、7/7、8/8；关键词不能代替人工相关性评审。回答来自固定虚构练习案例，未必充分回答随机题；不是人工金标，也没有修复后的真实模型成功率或性能结论。模型题目、评分、改进提纲和报告仍需人工逐题复核。

## 逐次失败归因与修复

全部102份原始正文均是可解析的完整JSON；未发现 evidence 非原文、分数越界或JSON截断导致这批失败。旧校验拒绝了25次评分输出和5次总结输出，其中部分在原有重试后成功。

- 11次最终评分失败中，9次有不来自 answer/resume 的 quote：护士好第2题（attempt 12/13）、护士差第4题（20/21）、人力好第3/5题（59/60、62/63）及差第5题（72/73）、教师好第1/3题（78/79、81/82）及差第1/3题（89/90、92/93）。模型把题干情境、JD或将题干“你”改为“我”的文字放进候选人经历。这是应继续拒绝的来源错误，不能直接放宽为接受题干/JD。score prompt现明确区分题设与本人原话，缺背景允许空quote加占位符；唯一一次修复请求携带具体字段错误和上次输出，取代原来的笼统提醒。
- 另外2次最终评分失败仅因纠正提示格式：财务差第1题（48/49）、教师差第5题（96/97）。旧规则只接受【补充…】，拒绝了【改为…】/【改写为…】。现接受有界、非嵌套的【补充…】【改为…】【请改为…】【改写为…】，仍作为提示而非已发生事实，继续拦截虚构quote、错误evidence、越界/非整数分数；无来源数字仍替换为数据占位符。销售差第2题（33）、教师差第4题（94）也有同类首轮失败。
- 护士差总结22/23诚实返回空strengths，旧prompt和校验强制至少1项；22还返回9条weaknesses。现允许无有据优点/短板时返回空数组，advice仍至少1条。销售好总结30（15条优势/20条短板）、人力好总结66、教师差总结101（各9条短板）也因8条硬上限触发无谓重试；现先校验所有条目（每数组最多64条、每条最多1000字），再按模型顺序保留前8条。缺字段、错误类型、空/超长条目、无建议、极端超量仍拒绝。

所有30次旧校验失败输出及必要虚构回答已提取到 `backend/offerpilot-interview/src/test/resources/fixtures/interview/cross-industry-failures.json`，去除网关/owner/路径等元数据，没有密钥或真人简历；完整网关正文留在仓库外。回放按12份可安全接受、18份仍需拒绝分类，不把拒绝真实来源错误称作修复后实模成功。额外合成测试覆盖JSON截断、证据伪造、分数边界、嵌套占位符及重试上限。

## 报告时延调整

报告对尚无evaluation的题最多并发3次评分，已有model/unavailable评价不重复评分，空回答直接保存未评分状态。每题仍独立请求、独立校验、最多一次结构修复，四维均分、顺序、来源和覆盖数计算不变；没有合并题目或改变rubric。并行阶段只计算，全部返回后在一个事务中检查同一revision并按题号保存，避免各题互相使revision过期；用户编辑或取消时不落过期批次，取消会中断并取消在途模型订阅。随后用新快照汇总并调用总结。并发上限为每个报告3，继续经过原owner配额和安全边界。

本地并发/取消/过期/部分失败回归验证了上述行为；未在网络环境测量修复后的实际提速，也不承诺15题端到端 <20s。先前规则题修复（旧软件技术模板11→0，六岗位15题降级去重8→15）继续保留，不重写已保存题目。

## 本轮验证与复现

使用Java17、test profile，清除DEEPSEEK_API_KEY和两个live开关，预载本机已有Byte Buddy agent；没有live调用。

- 面试模块 **70项全部通过**，包含30份真实fixture回放、一次修复上限和5项批量评分回归；agent面试审批/意图3项通过。
- bootstrap定向检查（Interview*Test、GenerationWorkTest、PromptInjectionCoverageTest）：16项中6通过、1条件跳过、9项面试HTTP用例因Docker不可用而错误，未验证真实PostgreSQL事务/HTTP生命周期。本轮没有可用docker命令或socket。
- 原样执行 `bash ./mvnw -B verify`：platform 71项中50通过、21项因本地监听socket权限 `Operation not permitted` 错误，后续模块未执行；不宣称默认完整verify通过。
- 为继续验证后续模块，仅通过CLI排除明确依赖socket/Docker的测试（不改测试源码或默认门禁），设置 `java.awt.headless=true` 解决当前DISPLAY不可用，运行整个reactor的离线verify：**479项，477通过、2项既有条件跳过，零失败/错误**，所有模块及bootstrap打包成功。其中bootstrap 73项，71通过、2跳过。第一次离线运行因X11导致3项简历PDF测试错误，headless后这些测试已正常执行通过，没有排除它们。
- `git diff --check`通过；原有未提交改动保留，未git提交；未改前端、未运行其门禁。

离线补充命令（排除范围显式列出，不等价于完整默认门禁）：

```bash
cd backend
JAVA_TOOL_OPTIONS='-Djava.awt.headless=true -javaagent:/home/box/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' \
  env -u DEEPSEEK_API_KEY -u OFFERPILOT_RUN_LIVE_INTERVIEW -u OFFERPILOT_RUN_LIVE_EVAL \
  SPRING_PROFILES_ACTIVE=test bash ./mvnw -B verify \
  '-Dtest=*Test,!*IntegrationTest,!*LiveEvaluation*,!DeepSeekGatewayTest,!ModelGatewayTest,!ParallelSafetyScreenTest,!PiiGatewayTransportTest,!LlmQuotaGatewayTest,!JobsMcpClientTimeoutTest,!JobPageFetcherPinningTest,!RedteamRegressionTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

本轮日志在仓库外：`/tmp/interview-repair-unit.log`、`/tmp/interview-repair-bootstrap.log`、`/tmp/interview-repair-verify.log`、`/tmp/interview-repair-offline-verify.log`、`/tmp/interview-repair-offline-headless-verify.log`。允许socket/Docker的环境仍需运行默认完整verify；修后真实模型成功率和时延仍需另行live复测。

live仅供后续允许网络环境手动复测（收费；本轮未执行）：

```bash
cd backend
OFFERPILOT_RUN_LIVE_INTERVIEW=true bash ./mvnw -B -Plive \
  -pl offerpilot-bootstrap -am test \
  -Dtest=InterviewCrossIndustryLiveEvaluationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dofferpilot.interview.output=/workspace/docs-eval-local/interview-cross-industry-2026-10-20 \
  -Dofferpilot.interview.phase=after-validation-fix
```

每次建立唯一子目录，不覆盖既有证据；密钥仅读取环境变量。生产FLASH为deepseek-flash、thinking disabled、8192 tokens、60秒idle，业务每次90秒、48000字符上限，结构/证据最多修复一次；主请求硬上限180，安全分类请求可能另收费。此评测使用真实 Intelligence/提示词/Gateway/aggregate/narrative，不覆盖数据库、HTTP或后台任务，也不替代容器集成验证。

## box 真模型复测（2026-10-20 09:20 CST，沙箱外）

| 岗位 | 题数 请求/实际 | 配比符合 | 技术泄漏 | 关键词相关 | 出题 s | 评分 s/题(串行) | 报告 s | 好/差均分 |
|---|---|---|---|---|---|---|---|---|
| 新媒体运营 | 3/3 | 是 | 0 | 3/3 | 2.6 | 3.4–4.0 | 3.2–3.7 | 44/18 |
| 护士 | 4/4 | 是 | 0 | 4/4 | 2.1 | 3.6–4.2 | 3.0–3.6 | 62/12 |
| 销售 | 5/5 | 是 | 0 | 5/5 | 3.0 | 3.9–4.5 | 3.6–4.2 | 60/15 |
| 财务 | 6/6 | 是 | 0 | 5/6 | 2.7 | 3.8–6.4 | 3.4–4.2 | 66/10 |
| 人力资源专员 | 7/7 | 是 | 0 | 7/7 | 2.2 | 3.4–5.3 | 3.8–4.0 | 62/14 |
| 幼儿园教师 | 8/8 | 是 | 0 | 8/8 | 2.8 | 3.4–4.2 | 3.3–4.4 | 59/11 |

逐题评分 66/66 有效、报告 12/12。首轮复测幼师 1 题差答评分两次被拒（占位符写成【从…分析】不符合【补充…】格式），已改为规范化为【补充：…】而非整题失败，并加单测；修后 6/6 DELIVERED。原始输出在仓库外，不提交。
