package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeTextParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResumeTextParserTests {

    @Test
    void fillsVietnameseCvSectionsWithoutInventingDatePrecision() {
        Map<String, Object> parsed = parse("""
            NGUYỄN VĂN AN
            Backend Developer
            Email: an.nguyen@example.com
            SĐT: +84 912 345 678
            Địa chỉ: Hà Nội
            GitHub: https://github.com/nguyenvanan
            LinkedIn: https://www.linkedin.com/in/nguyenvanan

            GIỚI THIỆU
            Kỹ sư backend xây dựng REST API cho hệ thống học tập.

            KỸ NĂNG
            Java, Spring Boot, PostgreSQL, Docker

            KINH NGHIỆM LÀM VIỆC
            Chức danh: Backend Developer
            Công ty: Công ty ABC
            01/2023 - Hiện tại
            - Xây dựng REST API bằng Java và Spring Boot.
            - Tối ưu truy vấn PostgreSQL.

            HỌC VẤN
            Trường: Đại học FPT
            Ngành: Kỹ thuật phần mềm
            Bằng cấp: Cử nhân
            09/2019 - 06/2023
            GPA: 3.5/4.0
            - Đồ án xây dựng hệ thống quản lý học tập.

            DỰ ÁN
            Tên dự án: Learning Portal
            Vai trò: Backend Developer
            Công nghệ: Java, PostgreSQL, Docker
            - Xây dựng API quản lý khóa học.

            CHỨNG CHỈ
            Tên: AWS Certified Cloud Practitioner
            Đơn vị cấp: Amazon Web Services
            2024
            """);

        assertEquals("NGUYỄN VĂN AN", parsed.get("full_name"));
        assertEquals("Backend Developer", parsed.get("position"));
        assertEquals("an.nguyen@example.com", parsed.get("email"));
        assertEquals("84912345678", parsed.get("phone").toString().replaceAll("\\D", ""));
        assertEquals("Hà Nội", parsed.get("location"));
        assertTrue(parsed.get("summary").toString().contains("REST API"));
        Map<?, ?> frontend = (Map<?, ?>) parsed.get("frontend_fields");
        assertEquals("https://github.com/nguyenvanan", frontend.get("github"));
        assertEquals("https://www.linkedin.com/in/nguyenvanan", frontend.get("linkedin"));
        assertTrue(skillNames(parsed).containsAll(List.of("Java", "Spring Boot", "PostgreSQL", "Docker")));

        Map<String, Object> experience = rows(parsed, "experience").getFirst();
        assertEquals("Backend Developer", experience.get("job_title"));
        assertEquals("Công ty ABC", experience.get("company_name"));
        assertEquals("01/2023", experience.get("display_start_date"));
        assertEquals(Boolean.TRUE, experience.get("is_current"));
        assertFalse(experience.containsKey("start_date"));
        assertFalse(experience.containsKey("end_date"));
        assertTrue(experience.get("description").toString().contains("Tối ưu truy vấn"));

        Map<String, Object> education = rows(parsed, "education").getFirst();
        assertEquals("Đại học FPT", education.get("school_name"));
        assertEquals("Kỹ thuật phần mềm", education.get("major"));
        assertEquals("Cử nhân", education.get("degree"));
        assertEquals("3.5/4.0", education.get("gpa"));
        assertEquals("09/2019", education.get("display_start_date"));
        assertEquals("06/2023", education.get("display_end_date"));
        assertTrue(education.get("highlights").toString().contains("Đồ án"));

        Map<String, Object> project = rows(parsed, "projects").getFirst();
        assertEquals("Learning Portal", project.get("project_name"));
        assertEquals("Backend Developer", project.get("role"));
        assertTrue(project.get("description").toString().contains("khóa học"));
        assertTrue(((List<?>) project.get("technologies")).contains("PostgreSQL"));

        Map<String, Object> certification = rows(parsed, "certifications").getFirst();
        assertEquals("AWS Certified Cloud Practitioner", certification.get("name"));
        assertEquals("Amazon Web Services", certification.get("issuer"));
        assertEquals("2024", certification.get("display_date"));
    }

    @Test
    void separatesEnglishRolesAndStopsAtUnrelatedSections() {
        Map<String, Object> parsed = parse("""
            Full name: Alice Nguyen
            Position: Software Engineer
            Contact: alice@example.org
            Skills: JavaScript, TS, React.js, C++, C#, ASP.NET

            WORK EXPERIENCE
            Software Engineer | Acme Ltd | 2022 - 2024
            - Built customer APIs using TypeScript.

            Backend Developer | Beta Inc | 2020 - 2022
            - Maintained billing services.

            REFERENCES
            Robert Smith
            Director at Reference Company
            robert@example.org
            """);

        assertEquals("Alice Nguyen", parsed.get("full_name"));
        assertEquals("alice@example.org", parsed.get("email"));
        assertEquals(2, rows(parsed, "experience").size());
        assertEquals("Software Engineer", rows(parsed, "experience").get(0).get("job_title"));
        assertEquals("Acme Ltd", rows(parsed, "experience").get(0).get("company_name"));
        assertEquals("Backend Developer", rows(parsed, "experience").get(1).get("job_title"));
        assertEquals("Beta Inc", rows(parsed, "experience").get(1).get("company_name"));
        assertFalse(rows(parsed, "experience").toString().contains("Robert"));
        assertTrue(skillNames(parsed).containsAll(List.of("JavaScript", "TypeScript", "C++", "C#", "ASP.NET")));
        assertFalse(skillNames(parsed).contains("Java"));
    }

    @Test
    void preservesFullDatesButDoesNotInventDatesForYearOnlyRanges() {
        Map<String, Object> parsed = parse("""
            EXPERIENCE
            Role: Developer
            Company: Example Ltd
            15/01/2023 - 31/12/2023
            - Built a scheduling service.

            EDUCATION
            School: Example University
            2018 - 2022
            """);

        Map<String, Object> experience = rows(parsed, "experience").getFirst();
        assertEquals("2023-01-15", experience.get("start_date"));
        assertEquals("2023-12-31", experience.get("end_date"));
        Map<String, Object> education = rows(parsed, "education").getFirst();
        assertEquals("2018", education.get("display_start_date"));
        assertEquals("2022", education.get("display_end_date"));
        assertFalse(education.containsKey("start_date"));
        assertFalse(education.containsKey("end_date"));
    }

    @Test
    void doesNotTurnDatesOrArbitrarySentencesIntoContactOrIdentityFields() {
        Map<String, Object> parsed = parse("""
            Built useful software for education and public services.
            01/2020 - 12/2024
            """);
        assertFalse(parsed.containsKey("phone"));
        assertFalse(parsed.containsKey("full_name"));
        assertFalse(parsed.containsKey("position"));
        assertTrue(rows(parsed, "experience").isEmpty());
        assertTrue(rows(parse(""), "skills").isEmpty());
    }

    @Test
    void malformedDatesAndLongDescriptionsStillProduceAValidReviewDocument() {
        Map<String, Object> parsed = assertDoesNotThrow(() -> parse("""
            EXPERIENCE
            Role: Developer
            Company: Example
            31/02/2024 - 01/01/2020
            """ + "- Delivered a useful feature.\n".repeat(1000)));
        Map<String, Object> experience = rows(parsed, "experience").getFirst();
        assertTrue(experience.get("description").toString().length() <= 20_000);
        assertFalse(experience.containsKey("start_date"));
    }

    @Test
    void keepsSkillsOutOfIgnoredReferenceAndInterestSections() {
        Map<String, Object> parsed = parse("""
            Skills: Java, PostgreSQL
            Interests
            Reading about Python and Docker.
            References
            JavaScript Developer at Example
            """);
        assertTrue(skillNames(parsed).containsAll(List.of("Java", "PostgreSQL")));
        assertFalse(skillNames(parsed).contains("Python"));
        assertFalse(skillNames(parsed).contains("JavaScript"));
    }

    @Test
    void preservesRepeatedProjectLabelsAndCompanyFirstExperienceHeaders() {
        Map<String, Object> parsed = parse("""
            EXPERIENCE
            Acme Ltd
            Developer
            2020 - 2021
            - Built APIs.
            Beta Ltd
            Engineer
            2022 - Present
            - Maintained services.

            PROJECTS
            Project: API
            Role: Developer
            Technologies: Java
            Project: Portfolio
            Role: Designer
            Technologies: TypeScript
            """);
        assertEquals(2, rows(parsed, "experience").size());
        assertEquals("Acme Ltd", rows(parsed, "experience").get(0).get("company_name"));
        assertEquals("Beta Ltd", rows(parsed, "experience").get(1).get("company_name"));
        assertEquals(2, rows(parsed, "projects").size());
        assertEquals("API", rows(parsed, "projects").get(0).get("project_name"));
        assertEquals("Portfolio", rows(parsed, "projects").get(1).get("project_name"));
        assertEquals("Designer", rows(parsed, "projects").get(1).get("role"));
    }

    @Test
    void neverUsesReferenceContactDetailsWhenCandidateContactsAreAbsent() {
        Map<String, Object> parsed = parse("""
            Full name: Alice Nguyen
            Skills: Java; want to learn Python
            REFERENCES
            Full name: Robert Smith
            Contact: robert@example.org
            Phone: +84 912 345 678
            GitHub: https://github.com/robertsmith
            """);
        assertEquals("Alice Nguyen", parsed.get("full_name"));
        assertFalse(parsed.containsKey("email"));
        assertFalse(parsed.containsKey("phone"));
        assertFalse(parsed.containsKey("frontend_fields"));
        assertEquals(List.of("Java"), skillNames(parsed));
    }

    @Test
    void boundsLargeUnstructuredSectionsAndDoesNotPromoteBulletsToProjectTitles() {
        Map<String, Object> parsed = parse("PROJECTS\n- REST API integration\n" + "- Useful feature\n".repeat(10_000));
        assertEquals(1, rows(parsed, "projects").size());
        Map<String, Object> project = rows(parsed, "projects").getFirst();
        assertFalse(project.containsKey("project_name"));
        assertTrue(project.get("description").toString().length() <= 20_000);
    }

    private static Map<String, Object> parse(
        String text) {
        return ResumeData.normalize(ResumeTextParser.parse(text));
    }

    @Test
    void rejectsMalformedEmailCandidatesAndHandlesLongPunctuationHeaders() {
        Map<String, Object> parsed = parse("Email: wrong..name@example.com\n" + "a=".repeat(80_000));
        assertFalse(parsed.containsKey("email"));
        assertEquals("valid.name@example.org", parse("Email: valid.name@example.org").get("email"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(
        Map<String, Object> document,
        String section) {
        return (List<Map<String, Object>>) document.get(section);
    }

    private static List<String> skillNames(
        Map<String, Object> document) {
        return rows(document, "skills").stream().map(row -> row.get("name").toString()).toList();
    }
}
