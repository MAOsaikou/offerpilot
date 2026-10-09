-- 保留历史报告与模型名，只统一档位标识；旧客户端仍由读取/请求兼容层映射。
UPDATE user_preferences SET default_model_tier='FLASH' WHERE upper(default_model_tier)='CHEAP';
UPDATE resume_model_preferences SET text_tier='FLASH' WHERE upper(text_tier)='CHEAP';
UPDATE resume_model_preferences SET vision_tier='FLASH' WHERE upper(vision_tier)='CHEAP';
UPDATE resumes SET text_model_tier='FLASH' WHERE upper(text_model_tier)='CHEAP';
UPDATE resumes SET vision_model_tier='FLASH' WHERE upper(vision_model_tier)='CHEAP';
UPDATE resume_diagnoses SET model_tier='FLASH' WHERE upper(model_tier)='CHEAP';
UPDATE resume_optimizations SET model_tier='FLASH' WHERE upper(model_tier)='CHEAP';
-- 保留 V2 的宽松 CHECK 以允许滚动升级期间旧版本写入 CHEAP；读者始终容错。
