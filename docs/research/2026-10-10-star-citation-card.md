# STAR 引用卡可见性

- 根因：`MessageCards` 白名单缺少 `star_story` / `star_story_candidates` / `job_eval_report`，聊天流里 `UiCard` 被丢弃，冒烟只能靠模型复述 `<star-story-data>`。
- 修复：补白名单；召回附加 `star_story_citations`（`{items,count}`）；候选卡 payload 改为对象。
