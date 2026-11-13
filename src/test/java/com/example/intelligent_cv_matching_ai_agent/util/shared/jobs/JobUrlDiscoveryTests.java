package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class JobUrlDiscoveryTests {

    @ParameterizedTest
    @CsvSource(
        value = {
            "ITVIEC|Hà Nội|city=ha-noi",
            "ITVIEC|Hanoi|city=ha-noi",
            "ITVIEC|Đà Nẵng|city=da-nang",
            "ITVIEC|Cần Thơ|city=can-tho",
            "ITVIEC|HCM|city=ho-chi-minh-hcm",
            "ITVIEC|Bắc Ninh|city=bac-ninh",
            "TOPCV|Hanoi|cityIds%5B%5D=1",
            "TOPCV|Đà Nẵng|cityIds%5B%5D=8",
            "TOPCV|CanTho|cityIds%5B%5D=20",
            "TOPCV|Hải Phòng|cityIds%5B%5D=9",
            "TOPCV|HCM|cityIds%5B%5D=2",
            "TOPCV|Huế|cityIds%5B%5D=61"},
        delimiter = '|'
    )
    void selectedLocationsUseTheSourceSearchFormValues(
        String source,
        String location,
        String parameter) {
        String base = JobUrlDiscovery.searchUrl(source, "C++ Java", 2);
        assertEquals(base + "&" + parameter, JobUrlDiscovery.searchUrl(source, "C++ Java", location, 2));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Toàn quốc", "All Cities", "Remote", "Đà Lạt", "Singapore"})
    void absentAllOrUnmappedLocationsNeverDefaultToHoChiMinh(
        String location) {
        for (String source : List.of("ITVIEC", "TOPCV")) {
            assertEquals(
                JobUrlDiscovery.searchUrl(source, "Java", 1),
                JobUrlDiscovery.searchUrl(source, "Java", location, 1)
            );
        }
    }

    @Test
    void overlongLocationIsRejectedBeforeDiscovery() {
        assertThrows(RuntimeException.class, () -> JobUrlDiscovery.searchUrl("ITVIEC", "Java", "a".repeat(121), 1));
    }

    @Test
    void discoveryResolvesRelativeDetailLinksAndExcludesListingsAdsAndForeignHosts() {
        String html = """
            <a href="/it-jobs/java-backend-company-1234?utm_source=list">Job</a>
            <a href="/it-jobs/java-backend-company-1234?lab_feature=preview#apply">Duplicate</a>
            <a href="/it-jobs/java">Listing</a>
            <a href="https://evil.example/it-jobs/java-backend-1234">Foreign</a>
            <a href="http://itviec.com/it-jobs/java-backend-1234">HTTP</a>
            <a href="https://itviec.com:443/it-jobs/java-backend-1234">Port</a>
            <a href="/companies/example">Company</a>
            <a href="/it-jobs/python-engineer-example-5678">Job 2</a>
            """;
        assertEquals(
            List.of(
                "https://itviec.com/it-jobs/java-backend-company-1234",
                "https://itviec.com/it-jobs/python-engineer-example-5678"
            ),
            JobUrlDiscovery.discoverLinks(html, "ITVIEC", "https://itviec.com/it-jobs?query=java", 20)
        );
    }

    @Test
    void topcvDetailPatternAndLimitAreAppliedBeforeReturningUrls() {
        String html = """
            <a href="/viec-lam/java-developer/123456.html?ta_source=search&u_sr_id=session">Job</a>
            <a href="/viec-lam/java-developer/123456.html">Duplicate</a>
            <a href="/tim-viec-lam-java">Listing</a>
            <a href="/viec-lam/devops/456789.html">Job 2</a>
            <a href="https://itviec.com/it-jobs/java-company-1234">Different source</a>
            """;
        assertEquals(
            List.of("https://www.topcv.vn/viec-lam/java-developer/123456.html"),
            JobUrlDiscovery.discoverLinks(html, "TOPCV", "https://www.topcv.vn/tim-viec-lam?keyword=java", 1)
        );
    }

    @Test
    void onlyKnownTrackingKeysAreRemovedAndUnknownQuerySemanticsRemain() {
        assertEquals(
            "https://itviec.com/it-jobs/java-company-1234?language=en&campaign_version=2",
            JobUrlDiscovery.canonicalUrl(
                "ITVIEC",
                "https://itviec.com/it-jobs/java-company-1234?%75tm_source=email&language=en"
                    + "&gclid=abc&campaign_version=2#apply"
            )
        );
    }

    @Test
    void searchUrlEncodesKeywordAndValidatesBoundedPages() {
        assertEquals(
            "https://itviec.com/it-jobs?query=C%2B%2B+Java&page=2",
            JobUrlDiscovery.searchUrl("ITVIEC", "C++ Java", 2)
        );
        assertEquals("https://www.topcv.vn/tim-viec-lam-java?page=1", JobUrlDiscovery.searchUrl("TOPCV", "Java", 1));
        assertThrows(RuntimeException.class, () -> JobUrlDiscovery.searchUrl("OTHER", "Java", 1));
        assertThrows(RuntimeException.class, () -> JobUrlDiscovery.searchUrl("ITVIEC", "Java", 11));
    }

    @Test
    void topCvSearchUsesKeywordPathAndKeepsPaginationAndLocation() {
        assertEquals("https://www.topcv.vn/tim-viec-lam-data?page=1", JobUrlDiscovery.searchUrl("TOPCV", "Data", 1));
        assertEquals(
            "https://www.topcv.vn/tim-viec-lam-data-analyst?page=2&cityIds%5B%5D=1",
            JobUrlDiscovery.searchUrl("TOPCV", " Data Analyst ", "Ha Noi", 2)
        );
        assertEquals(
            "https://www.topcv.vn/tim-viec-lam-ky-su-du-lieu?page=1",
            JobUrlDiscovery.searchUrl("TOPCV", "Kỹ sư dữ liệu", 1)
        );
        assertEquals("https://www.topcv.vn/tim-viec-lam-c%2B%2B?page=1", JobUrlDiscovery.searchUrl("TOPCV", "C++", 1));
    }
}
