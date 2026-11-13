\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

CREATE TEMP TABLE reset_jobs ON COMMIT DROP AS
SELECT id FROM public.market_job_posts WHERE ingestion_method IN ('CRAWL','SCHEDULED_CRAWL');
CREATE TEMP TABLE reset_rows(rel oid, row_tid tid, PRIMARY KEY(rel,row_tid)) ON COMMIT DROP;
INSERT INTO reset_rows SELECT 'public.market_job_posts'::regclass,ctid FROM public.market_job_posts
WHERE id IN (SELECT id FROM reset_jobs);
-- Remove only matching runs whose existing results all belong to the selected crawled jobs.
INSERT INTO reset_rows SELECT 'public.job_matching_runs'::regclass,r.ctid FROM public.job_matching_runs r
WHERE EXISTS (SELECT 1 FROM public.job_match_results m WHERE m.matching_run_id=r.id
    AND m.market_job_id IN (SELECT id FROM reset_jobs))
AND NOT EXISTS (SELECT 1 FROM public.job_match_results m WHERE m.matching_run_id=r.id
    AND m.market_job_id NOT IN (SELECT id FROM reset_jobs));

CREATE FUNCTION pg_temp.fk_join(constraint_id oid, child_alias text, parent_alias text) RETURNS text
LANGUAGE sql AS $$
SELECT string_agg(format('%I.%I = %I.%I', child_alias,child.attname,parent_alias,parent.attname),' AND ' ORDER BY k.ord)
FROM pg_constraint fk
CROSS JOIN LATERAL unnest(fk.conkey,fk.confkey) WITH ORDINALITY k(child_num,parent_num,ord)
JOIN pg_attribute child ON child.attrelid=fk.conrelid AND child.attnum=k.child_num
JOIN pg_attribute parent ON parent.attrelid=fk.confrelid AND parent.attnum=k.parent_num
WHERE fk.oid=constraint_id;
$$;

DO $$
DECLARE fk record; added bigint; total_added bigint;
BEGIN
    LOOP
        total_added:=0;
        FOR fk IN SELECT oid,conrelid,confrelid FROM pg_constraint
            WHERE contype='f' AND confdeltype NOT IN ('n','d') LOOP
            EXECUTE format('INSERT INTO reset_rows SELECT %s,ch.ctid FROM %s ch JOIN %s pa ON %s '
                ||'JOIN reset_rows target ON target.rel=%s AND target.row_tid=pa.ctid ON CONFLICT DO NOTHING',
                fk.conrelid,fk.conrelid::regclass,fk.confrelid::regclass,
                pg_temp.fk_join(fk.oid,'ch','pa'),fk.confrelid);
            GET DIAGNOSTICS added=ROW_COUNT;
            total_added:=total_added+added;
        END LOOP;
        EXIT WHEN total_added=0;
    END LOOP;
    IF EXISTS (SELECT 1 FROM reset_rows WHERE rel IN
        ('public.users'::regclass,'public.resumes'::regclass,'public.candidate_profiles'::regclass,
         'public.candidate_profile_skills'::regclass,'public.skills'::regclass)) THEN
        RAISE EXCEPTION 'Deletion would affect protected account/CV/profile/catalog data';
    END IF;
END $$;

SELECT rel::regclass AS table_name,count(*) AS rows_selected FROM reset_rows GROUP BY rel ORDER BY rel::regclass::text;
SELECT count(*) AS crawled_jobs_selected FROM reset_jobs;

\if :execute
-- Protect source rows and all dependent tables against concurrent writes during maintenance.
DO $$ DECLARE t record; BEGIN
    FOR t IN SELECT DISTINCT rel FROM reset_rows ORDER BY rel LOOP
        EXECUTE format('LOCK TABLE %s IN SHARE ROW EXCLUSIVE MODE',t.rel::regclass);
    END LOOP;
END $$;
CREATE TEMP TABLE reset_protected(table_name text PRIMARY KEY, fingerprint text) ON COMMIT DROP;
DO $$ DECLARE name text; digest text; BEGIN
    FOREACH name IN ARRAY ARRAY['users','resumes','candidate_profiles','candidate_profile_skills','skills'] LOOP
        EXECUTE format('SELECT md5(coalesce(string_agg(to_jsonb(t)::text,E''\n'' ORDER BY to_jsonb(t)::text),'''')) FROM public.%I t',name) INTO digest;
        INSERT INTO reset_protected VALUES(name,digest);
    END LOOP;
    SELECT md5(coalesce(string_agg(to_jsonb(j)::text,E'\n' ORDER BY j.id),'')) INTO digest
    FROM public.market_job_posts j WHERE j.id NOT IN (SELECT id FROM reset_jobs);
    INSERT INTO reset_protected VALUES('preserved_jobs',digest);
END $$;

DO $$
DECLARE t record; fk record; guard text; affected bigint; progress bigint;
BEGIN
    LOOP
        progress:=0;
        FOR t IN SELECT DISTINCT rel FROM reset_rows ORDER BY rel LOOP
            guard:='';
            FOR fk IN SELECT oid,conrelid FROM pg_constraint WHERE contype='f'
                AND confrelid=t.rel AND confdeltype IN ('a','r') LOOP
                guard:=guard||format(' AND NOT EXISTS (SELECT 1 FROM %s ch WHERE %s)',
                    fk.conrelid::regclass,pg_temp.fk_join(fk.oid,'ch','pa'));
            END LOOP;
            EXECUTE format('DELETE FROM %s pa USING reset_rows target WHERE target.rel=%s '
                ||'AND target.row_tid=pa.ctid %s', t.rel::regclass,t.rel,guard);
            GET DIAGNOSTICS affected=ROW_COUNT;
            progress:=progress+affected;
            EXECUTE format('DELETE FROM reset_rows target WHERE target.rel=%s '
                ||'AND NOT EXISTS (SELECT 1 FROM %s pa WHERE pa.ctid=target.row_tid)',t.rel,t.rel::regclass);
        END LOOP;
        EXIT WHEN NOT EXISTS(SELECT 1 FROM reset_rows);
        IF progress=0 THEN RAISE EXCEPTION 'Dependency cycle or unexpected references; transaction rolled back'; END IF;
    END LOOP;
END $$;

DO $$ DECLARE saved record; digest text; BEGIN
    FOR saved IN SELECT * FROM reset_protected LOOP
        IF saved.table_name='preserved_jobs' THEN
            SELECT md5(coalesce(string_agg(to_jsonb(j)::text,E'\n' ORDER BY j.id),'')) INTO digest
            FROM public.market_job_posts j WHERE j.id NOT IN (SELECT id FROM reset_jobs);
        ELSE
            EXECUTE format('SELECT md5(coalesce(string_agg(to_jsonb(t)::text,E''\n'' ORDER BY to_jsonb(t)::text),'''')) FROM public.%I t',saved.table_name) INTO digest;
        END IF;
        IF digest IS DISTINCT FROM saved.fingerprint THEN
            RAISE EXCEPTION 'Protected data changed: %',saved.table_name;
        END IF;
    END LOOP;
    IF EXISTS(SELECT 1 FROM public.market_job_posts WHERE id IN (SELECT id FROM reset_jobs)) THEN
        RAISE EXCEPTION 'Some selected crawled jobs still exist';
    END IF;
END $$;
SELECT ingestion_method,status,count(*) FROM public.market_job_posts GROUP BY 1,2 ORDER BY 1,2;
COMMIT;
\else
ROLLBACK;
\endif
