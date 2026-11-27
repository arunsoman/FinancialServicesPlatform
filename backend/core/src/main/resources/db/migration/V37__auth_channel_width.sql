-- V37: the auth-plane channel columns were VARCHAR(4) while their own CHECK allows 'EMAIL'
-- (5 characters) — since V30 every EMAIL row failed to insert ("value too long"): no
-- password-reset, MFA or activation email was ever queued. Widen to fit the allowed values.
ALTER TABLE fsp_notifications.auth_dispatches ALTER COLUMN channel TYPE VARCHAR(5);
ALTER TABLE user_mfa                          ALTER COLUMN channel TYPE VARCHAR(5);
ALTER TABLE mfa_challenges                    ALTER COLUMN channel TYPE VARCHAR(5);
