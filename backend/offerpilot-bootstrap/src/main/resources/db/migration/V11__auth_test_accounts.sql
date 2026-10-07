-- 登录功能测试账号（固定 ID 与邮箱，ON CONFLICT 保证幂等）
-- 用户端：user@offerpilot.local / user123456
-- 客服端：staff@offerpilot.local / staff123456
INSERT INTO users (id, email, password_hash, nickname, roles, is_demo, status)
VALUES
  ('usr_test_user001', 'user@offerpilot.local',
   '$2a$12$jKUIXi89Ep/9wQ8zp9BWa.p5upC0gCt37J3jXhdFJ/BwggFm/RiZi',
   '测试用户', '{USER}', false, 'ACTIVE'),
  ('usr_test_staff001', 'staff@offerpilot.local',
   '$2a$12$Lu9rC7PE8YSdGM16NL1Js.qNJuwG/bKQrD4izbw626GuvNJaD4bX.',
   '测试客服', '{STAFF}', false, 'ACTIVE')
ON CONFLICT (email) DO NOTHING;
