-- 仅供已执行旧功能分支 V11/V12/V13 的 public schema 数据库。
-- 执行前停止应用并备份数据库；不会重建、删除或修改业务数据。
-- 新库和 main 数据库无需运行。未知或被修改过的历史拒绝处理。
BEGIN;
LOCK TABLE public.flyway_schema_history IN EXCLUSIVE MODE;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version = '11' AND script = 'V11__resume_patch_batches.sql') THEN
    IF EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version = '13' AND script = 'V13__resume_patch_batches.sql' AND checksum = -230072270 AND success) THEN
      RAISE NOTICE 'Legacy feature history is already aligned; no changes.';
      RETURN;
    END IF;
    RAISE EXCEPTION 'Not a recognized legacy feature database; no changes.';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.flyway_schema_history WHERE (version IS NULL OR version::numeric >= 11) AND (
      success AND type = 'SQL' AND (
        (version = '11' AND script = 'V11__resume_patch_batches.sql' AND description = 'resume patch batches' AND checksum = -230072270)
        OR (version = '12' AND script = 'V12__resume_optimizations.sql' AND description = 'resume optimizations' AND checksum = -115544576)
        OR (version = '13' AND script = 'V13__applications.sql' AND description = 'applications' AND checksum = 427451572)
      )
    ) IS NOT TRUE
  ) OR EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success) THEN
    RAISE EXCEPTION 'Unknown, modified or failed migration history; no changes.';
  END IF;
  IF to_regclass('public.resume_patch_batches') IS NULL
     OR (EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version = '12') AND to_regclass('public.resume_optimizations') IS NULL)
     OR (EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version = '13') AND to_regclass('public.applications') IS NULL) THEN
    RAISE EXCEPTION 'Legacy schema does not match migration history; no changes.';
  END IF;
  IF to_regclass('public.offerpilot_legacy_flyway_history_backup') IS NOT NULL THEN
    RAISE EXCEPTION 'History backup already exists; inspect previous operation before retrying.';
  END IF;
  CREATE TABLE public.offerpilot_legacy_flyway_history_backup AS SELECT * FROM public.flyway_schema_history;
  UPDATE public.flyway_schema_history SET
    version = CASE version WHEN '11' THEN '13' WHEN '12' THEN '14' WHEN '13' THEN '15' END,
    script = CASE version WHEN '11' THEN 'V13__resume_patch_batches.sql' WHEN '12' THEN 'V14__resume_optimizations.sql' WHEN '13' THEN 'V15__applications.sql' END
  WHERE version IN ('11', '12', '13');
END $$;
COMMIT;
-- 下一次启动仅本次使用 SPRING_FLYWAY_OUT_OF_ORDER=true，补执行认证 V11/V12。
-- 补齐后移除该环境变量，后续仍按默认严格顺序升级。
