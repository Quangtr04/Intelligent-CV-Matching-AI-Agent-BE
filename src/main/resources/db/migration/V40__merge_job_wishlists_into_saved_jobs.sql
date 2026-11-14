-- saved_jobs is the sole source for candidate saved jobs.
-- Some manually imported diagrams/databases may contain the legacy proposed table.
DO $$
BEGIN
    IF to_regclass('public.job_wishlists') IS NOT NULL THEN
        LOCK TABLE public.job_wishlists IN ACCESS EXCLUSIVE MODE;
        INSERT INTO public.saved_jobs (user_id, market_job_id, created_at)
        SELECT user_id, market_job_id, min(created_at)
        FROM public.job_wishlists
        GROUP BY user_id, market_job_id
        ON CONFLICT (user_id, market_job_id) DO UPDATE
        SET created_at = LEAST(saved_jobs.created_at, EXCLUDED.created_at);

        -- Deliberately avoid CASCADE so unexpected dependencies cannot be removed silently.
        DROP TABLE public.job_wishlists;
    END IF;
END $$;
