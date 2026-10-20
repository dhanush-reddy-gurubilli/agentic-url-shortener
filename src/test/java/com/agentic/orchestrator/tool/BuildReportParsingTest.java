package com.agentic.orchestrator.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** How a Maven run is turned into the facts the validation gate and the agents use. */
class BuildReportParsingTest {

    @TempDir
    Path dir;

    private static Element testCase(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    @Test
    void coverageIsTheShortenerPackagesLineCoverageFromTheJacocoCsv() throws Exception {
        Path csv = dir.resolve("jacoco.csv");
        Files.writeString(csv, String.join("\n",
                "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED",
                "app,com.agentic.shortener.service,ShortenerService,10,90,1,9,2,18,1,5,0,4",
                "app,com.agentic.shortener.web,UrlController,0,50,0,0,0,10,0,3,0,3",
                "app,com.agentic.orchestrator.engine,StageRunner,500,10,50,1,400,5,30,1,20,1"));

        // shortener only: covered 28, missed 2 -> 28/30; the orchestrator row must not count
        assertThat(MavenBuildRunner.readCoverage(csv)).isCloseTo(28.0 / 30.0, within(1e-9));
    }

    @Test
    void aMissingOrEmptyCoverageReportIsZeroNotAnError() throws Exception {
        assertThat(MavenBuildRunner.readCoverage(dir.resolve("absent.csv"))).isZero();
        Path header = dir.resolve("header-only.csv");
        Files.writeString(header, "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\n");
        assertThat(MavenBuildRunner.readCoverage(header)).isZero();
    }

    @Test
    void aFailureCarriesItsMessageAndTheFirstProductionFrame() throws Exception {
        Element testCase = testCase("""
                <testcase classname="com.agentic.shortener.UrlApiTest" name="redirectIsA302">
                  <failure message="expected 302 but was 500&#10;second line"><![CDATA[org.opentest4j.AssertionFailedError: expected 302
                	at org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:55)
                	at com.agentic.shortener.service.ShortenerService.resolve(ShortenerService.java:61)
                	at com.agentic.shortener.UrlApiTest.redirectIsA302(UrlApiTest.java:20)]]></failure>
                </testcase>""");
        Element failure = (Element) testCase.getElementsByTagName("failure").item(0);

        assertThat(MavenBuildRunner.describeFailure(testCase, failure)).isEqualTo(
                "com.agentic.shortener.UrlApiTest.redirectIsA302: expected 302 but was 500 "
                        + "[at com.agentic.shortener.service.ShortenerService.resolve(ShortenerService.java:61)]");
    }

    @Test
    void aFailureWithNoProductionFrameStillNamesTheTestAndTheMessage() throws Exception {
        Element testCase = testCase("""
                <testcase classname="com.agentic.shortener.ExpiryDefectTest" name="aLinkExpiringExactlyNowIsGone">
                  <failure message="Expecting code to raise a throwable"><![CDATA[at com.agentic.shortener.ExpiryDefectTest.aLinkExpiringExactlyNowIsGone(ExpiryDefectTest.java:60)]]></failure>
                </testcase>""");
        Element failure = (Element) testCase.getElementsByTagName("failure").item(0);

        assertThat(MavenBuildRunner.describeFailure(testCase, failure))
                .isEqualTo("com.agentic.shortener.ExpiryDefectTest.aLinkExpiringExactlyNowIsGone: Expecting code to raise a throwable");
    }

    @Test
    void theLogSummaryKeepsErrorFailureAndTestCountLinesAndBoundsItsSize() {
        String log = "[INFO] Scanning for projects\n[INFO] Building x\n[ERROR] COMPILATION ERROR\n[ERROR] /x/A.java:[3,1] boom\n"
                + "[INFO] Tests run: 12, Failures: 1\n[WARNING] something\n[INFO] BUILD FAILURE\n";
        assertThat(MavenBuildRunner.summarize(log)).contains("COMPILATION ERROR").contains("A.java:[3,1] boom")
                .contains("Tests run: 12").doesNotContain("Scanning for projects").doesNotContain("[WARNING]");

        String noisy = "[ERROR] line\n".repeat(5000);
        assertThat(MavenBuildRunner.summarize(noisy).length()).isLessThanOrEqualTo(6100);
    }

    @Test
    void whenNothingLooksLikeAnErrorTheTailOfTheLogIsKeptInstead() {
        assertThat(MavenBuildRunner.summarize("[INFO] a\n[INFO] b\n[INFO] c")).contains("[INFO] c");
    }
}
