-- AI review of candidate-written cover letters and application emails.
-- Each tier starts with the same quota as application writing.
INSERT INTO subscription_quotas(tier_id,feature_code,quota_value,quota_period)
SELECT tier_id,'APPLICATION_REVIEW',quota_value,quota_period
FROM subscription_quotas
WHERE feature_code='APPLICATION_EMAIL'
ON CONFLICT(tier_id,feature_code) DO NOTHING;
