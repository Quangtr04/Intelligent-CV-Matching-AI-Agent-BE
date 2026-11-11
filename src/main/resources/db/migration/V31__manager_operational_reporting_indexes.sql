-- Support operational monitoring pages without exposing provider credentials or candidate CV contents.
CREATE INDEX ix_user_subscriptions_reporting ON user_subscriptions(created_at DESC,id);
CREATE INDEX ix_payment_orders_reporting ON payment_orders(created_at DESC,id);
CREATE INDEX ix_invoices_reporting ON invoices(issued_at DESC,id);
CREATE INDEX ix_ai_usage_logs_reporting ON ai_usage_logs(created_at,feature_code,user_id);
CREATE INDEX ix_market_crawl_runs_reporting ON market_crawl_runs(started_at DESC,id);
