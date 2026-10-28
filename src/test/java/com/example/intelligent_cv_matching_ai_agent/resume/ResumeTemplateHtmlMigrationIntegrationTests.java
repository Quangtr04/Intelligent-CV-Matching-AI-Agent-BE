package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.flywaydb.core.Flyway;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ResumeTemplateHtmlMigrationIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres")
    );

    @Test
    void upgradesExistingPresetLayoutsToHtmlWithoutChangingCustomHtmlMetadataOrCvReferences() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("18").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        );
        UUID standard = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID plum = UUID.fromString("30000000-0000-0000-0000-000000000002");
        UUID custom = UUID.randomUUID();
        UUID resume = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String order = "[\"certifications\",\"skills\",\"projects\",\"education\",\"experience\",\"summary\"]";
        jdbc.update(
            "UPDATE resume_templates SET layout_config=jsonb_build_object('layout','STANDARD_ATS',"
                + "'accentColor','#F0E0D0','fontFamily','Times New Roman','fontSize',16,'sectionOrder',?::jsonb) "
                + "WHERE id=?",
            order,
            standard
        );
        jdbc.update(
            "UPDATE resume_templates SET layout_config=jsonb_set("
                + "jsonb_set(layout_config,'{fontFamily}','\"Calibri\"'),'{fontSize}','13') WHERE id=?",
            plum
        );
        String customHtml = "<!DOCTYPE html><html lang=\"vi\"><head><style>body{color:#123456}</style></head>"
            + "<body><!-- Keep the administrator document unchanged --><h1 data-cv-field=\"fullName\"></h1>"
            + "<section data-cv-section=\"summary\"><h2>Mục tiêu</h2></section></body></html>";
        jdbc.update(
            "INSERT INTO resume_templates(id,name,language,is_active,is_trial_enabled,html_content) "
                + "VALUES(?,'Custom HTML','vi',false,true,?)",
            custom,
            customHtml
        );
        jdbc.update(
            "INSERT INTO users(id,email,full_name,password_hash,role,status) "
                + "VALUES(?,?,'HTML Migration Test','test-only-unusable-hash','CANDIDATE','ACTIVE')",
            owner,
            "html-migration-" + owner + "@example.test"
        );
        jdbc.update(
            "INSERT INTO resumes(id,user_id,title,source,template_id) VALUES(?,?,'Existing CV','MANUAL',?)",
            resume,
            owner,
            custom
        );
        String metadataQuery = "SELECT id,name,career_path,description,language,is_active,is_trial_enabled,"
            + "created_at FROM resume_templates ORDER BY id";
        var before = jdbc.queryForList(metadataQuery);
        var customUpdatedAt =
            jdbc.queryForObject("SELECT updated_at FROM resume_templates WHERE id=?", OffsetDateTime.class, custom);
        var standardUpdatedAt =
            jdbc.queryForObject("SELECT updated_at FROM resume_templates WHERE id=?", OffsetDateTime.class, standard);

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load()
            .migrate();

        assertEquals(before, jdbc.queryForList(metadataQuery));
        assertEquals(
            customUpdatedAt,
            jdbc.queryForObject("SELECT updated_at FROM resume_templates WHERE id=?", OffsetDateTime.class, custom)
        );
        // The existing updated_at trigger records the representation change for backfilled rows.
        assertFalse(
            jdbc.queryForObject("SELECT updated_at FROM resume_templates WHERE id=?", OffsetDateTime.class, standard)
                .isBefore(standardUpdatedAt)
        );
        assertEquals(customHtml, html(jdbc, custom));
        assertEquals(custom, jdbc.queryForObject("SELECT template_id FROM resumes WHERE id=?", UUID.class, resume));
        String standardHtml = html(jdbc, standard);
        String plumHtml = html(jdbc, plum);
        assertNotNull(ResumeTemplateHtml.normalize(standardHtml));
        assertNotNull(ResumeTemplateHtml.normalize(plumHtml));
        var standardDocument = Jsoup.parse(standardHtml);
        assertEquals("en", standardDocument.selectFirst("html").attr("lang"));
        assertEquals(
            List.of("certifications", "skills", "projects", "education", "experience", "summary"),
            standardDocument.select("[data-cv-section]").eachAttr("data-cv-section")
        );
        String css = standardDocument.selectFirst("style").data();
        assertTrue(css.contains("font-family:\"Times New Roman\",Times,serif;font-size:16pt"));
        assertTrue(css.contains("border-bottom:2px solid #F0E0D0"));
        assertTrue(css.contains("color:#334155"));
        assertFalse(css.contains("grid-template-columns"));
        var plumDocument = Jsoup.parse(plumHtml);
        assertEquals("vi", plumDocument.selectFirst("html").attr("lang"));
        assertEquals("Giới thiệu", plumDocument.selectFirst("[data-cv-section=summary] h2").text());
        assertTrue(
            plumDocument.selectFirst("style").data().contains("font-family:Calibri,Arial,sans-serif;font-size:13pt")
        );
        assertTrue(plumDocument.selectFirst("style").data().contains("grid-template-columns:30% 70%"));
        assertTrue(plumDocument.selectFirst("style").data().contains("background:#703D5712"));
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' "
                    + "AND table_name='resume_templates' AND column_name='layout_config'",
                Integer.class
            )
        );
        assertEquals(
            "NO",
            jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' "
                    + "AND table_name='resume_templates' AND column_name='html_content'",
                String.class
            )
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update("INSERT INTO resume_templates(name,language) VALUES('Missing HTML','vi')")
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update("UPDATE resume_templates SET html_content='   ' WHERE id=?", custom)
        );
    }

    private String html(
        JdbcTemplate jdbc,
        UUID id) {
        return jdbc.queryForObject("SELECT html_content FROM resume_templates WHERE id=?", String.class, id);
    }
}
