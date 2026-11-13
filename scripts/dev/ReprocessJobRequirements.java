import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobRequirementReprocessor;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Offline maintenance: JDBC and the local extractor only, no Spring app startup or AI workers. */
public final class ReprocessJobRequirements {

    public static void main(
        String[] arguments)
        throws Exception {
        boolean apply = arguments.length == 2 && arguments[0].equals("--apply");
        if (!apply && !(arguments.length == 1 && arguments[0].equals("--preview"))) {
            throw new IllegalArgumentException("Usage: --preview OR --apply BACKUP.json");
        }
        var source = new DriverManagerDataSource(
            Objects.requireNonNull(System.getenv("JOB_REPROCESS_DB_URL")),
            Objects.requireNonNull(System.getenv("JOB_REPROCESS_DB_USER")),
            Objects.requireNonNull(System.getenv("JOB_REPROCESS_DB_PASSWORD"))
        );
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(30);
        var store = new SqlStore(jdbc);
        String selection = ("NOT j.requirement_extraction_verified AND j.ingestion_method IN "
            + "('CRAWL','SCHEDULED_CRAWL') AND j.reviewed_by_user_id IS NULL AND "
            + "(j.requirement_extraction->>'version' IS DISTINCT FROM ? OR "
            + "j.requirement_extraction->>'catalogVersion' IS DISTINCT FROM ?)");
        var rows = store.list(
            ("SELECT j.id,j.raw_content_hash,j.requirement_extraction, (SELECT "
                + "jsonb_agg(to_jsonb(s)) FROM market_job_skills s WHERE "
                + "s.market_job_id=j.id) AS skills FROM market_job_posts j WHERE ") + selection + " ORDER BY j.id",
            JobRequirementExtractor.VERSION,
            SkillCatalog.get().version()
        );
        System.out.println("Eligible crawled jobs: " + rows.size());
        System.out.println("Extraction version: " + JobRequirementExtractor.VERSION);
        System.out.println("Catalog version: " + SkillCatalog.get().version());
        if (!apply) {
            return;
        }
        Path backup = Path.of(arguments[1]).toAbsolutePath().normalize();
        Files.writeString(backup, JsonMapper.builder().build().writeValueAsString(rows), StandardOpenOption.CREATE_NEW);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var reprocessor = new JobRequirementReprocessor(store);
        int total = 0;
        int processed;
        do {
            processed = Objects.requireNonNull(tx.execute(status -> reprocessor.reprocessBatch(100)));
            total += processed;
        } while (processed > 0);
        System.out.println("Reprocessed jobs: " + total);
        System.out.println("Backup: " + backup);
    }
}
