-- 운영은 ddl-auto=none이므로 배포 전에 대상 DB에 적용합니다.
-- 프로필 이미지는 S3 object key만 저장하며 URL은 저장하지 않습니다. 이미지가 없으면 NULL입니다.
-- 컬럼이 이미 있으면 유지합니다. 기존 회원 데이터는 변경하지 않습니다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_key VARCHAR(700);
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_updated_at TIMESTAMPTZ;

-- 최종 DB 설계서 기준: profile_image_key는 UNIQUE입니다. NULL은 여러 행에 허용됩니다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_profile_image_key ON users (profile_image_key);
