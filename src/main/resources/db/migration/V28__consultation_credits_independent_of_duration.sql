-- One credit funds one consultation lasting 30-60 minutes.
-- Keep historical orders and their financial snapshots unchanged.
UPDATE consultation_services
SET display_name = 'Gói 1 lượt tư vấn',
    description = 'Một lượt tư vấn với cố vấn. Mỗi lượt có thời gian từ 30 đến 60 phút.',
    updated_at = now()
WHERE service_code = 'CAREER_CALL_30';

UPDATE consultation_services
SET display_name = 'Gói 5 lượt tư vấn',
    description = 'Năm lượt tư vấn với cố vấn. Mỗi lượt có thời gian từ 30 đến 60 phút.',
    updated_at = now()
WHERE service_code = 'CAREER_PACK_5_30';

-- Retire duplicate duration-based offers; existing paid credits remain usable.
UPDATE consultation_services SET is_active = false, updated_at = now()
WHERE service_code IN ('CAREER_CALL_60', 'CAREER_PACK_5_60');
