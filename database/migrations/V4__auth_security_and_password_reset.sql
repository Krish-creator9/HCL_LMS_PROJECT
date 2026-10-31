-- V4: Authentication Enhancements (Google OAuth, Avatar, Password Reset)
ALTER TABLE user_account 
  ADD COLUMN reset_token VARCHAR(120) NULL,
  ADD COLUMN reset_token_expiry TIMESTAMP NULL,
  ADD COLUMN google_id VARCHAR(120) NULL,
  ADD COLUMN avatar_url VARCHAR(255) NULL;

CREATE INDEX idx_user_reset_token ON user_account(reset_token);
CREATE INDEX idx_user_google_id ON user_account(google_id);
