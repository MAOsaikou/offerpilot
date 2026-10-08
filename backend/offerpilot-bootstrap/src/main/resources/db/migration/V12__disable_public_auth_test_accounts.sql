-- V11 已共享，保持其校验和；保留数据并停用尚未轮换公开测试密码的账号。
-- 已改过邮箱或密码的账号不会被本迁移覆盖。
UPDATE users SET status = 'DISABLED'
WHERE status = 'ACTIVE' AND (
  (id = 'usr_test_user001' AND email = 'user@offerpilot.local'
   AND password_hash = '$2a$12$jKUIXi89Ep/9wQ8zp9BWa.p5upC0gCt37J3jXhdFJ/BwggFm/RiZi')
  OR
  (id = 'usr_test_staff001' AND email = 'staff@offerpilot.local'
   AND password_hash = '$2a$12$Lu9rC7PE8YSdGM16NL1Js.qNJuwG/bKQrD4izbw626GuvNJaD4bX.')
);
