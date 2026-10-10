-- Usage: psql <connection options> -v admin_email='you@example.com' -f scripts/bootstrap-admin.sql
-- Promote an already verified account only when no active administrator exists.
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('edunexus-bootstrap-admin'));
WITH promoted AS (
  UPDATE users SET role='ADMIN',updated_at=now()
  WHERE email=lower(:'admin_email') AND status='ACTIVE' AND email_verified_at IS NOT NULL
    AND NOT EXISTS(SELECT 1 FROM users WHERE role='ADMIN' AND status='ACTIVE')
  RETURNING id,email,role
), logged AS (
  INSERT INTO audit_logs(actor_user_id,action,entity_type,entity_id,new_value)
  SELECT id,'BOOTSTRAP_ADMIN','users',id,jsonb_build_object('role','ADMIN') FROM promoted
  RETURNING id
)
SELECT id,email,role FROM promoted;
COMMIT;
