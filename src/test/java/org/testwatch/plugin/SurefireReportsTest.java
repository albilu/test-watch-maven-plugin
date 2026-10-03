package org.testwatch.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SurefireReportsTest {
    @TempDir Path reports;

    @Test void ignoresOldReportsEvenWhenThePreviousSuitePassed() throws Exception {
        report("Old", "<testsuite tests=\"9\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        var before = SurefireReports.snapshot(Set.of(reports));
        assertFalse(SurefireReports.readFresh(Set.of(reports), before).found);
        report("New", "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\"><testcase classname=\"example.NewTest\"><failure/></testcase></testsuite>");
        var result = SurefireReports.readFresh(Set.of(reports), before);
        assertArrayEquals(new int[]{1, 1, 0, 0}, result.counts);
        assertEquals(Set.of("example.NewTest"), result.failed);
    }

    @Test void capturedXmlIsNotAResultAndNestedFailuresSelectTheEnclosingTest() throws Exception {
        report("Output", "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"example.PassingTest\"><system-out><![CDATA[<failure>not a failure</failure>]]></system-out></testcase></testsuite>");
        assertTrue(SurefireReports.readFresh(Set.of(reports), Map.of()).failed.isEmpty());
        report("Nested", "<testsuite tests=\"1\" failures=\"0\" errors=\"1\" skipped=\"0\"><testcase classname=\"example.OuterTest$Nested\"><error/></testcase></testsuite>");
        assertEquals(Set.of("example.OuterTest"), SurefireReports.readFresh(Set.of(reports), Map.of()).failed);
    }

    @Test void rejectsExternalEntitiesInsteadOfReadingLocalFiles() throws Exception {
        Path secret = reports.resolve("secret.txt");
        Files.writeString(secret, "must not be read");
        report("Unsafe", "<!DOCTYPE testsuite [<!ENTITY external SYSTEM \"" + secret.toUri() + "\">]><testsuite tests=\"1\">&external;</testsuite>");
        assertThrows(Exception.class, () -> SurefireReports.readFresh(Set.of(reports), Map.of()));
    }

    private void report(String name, String xml) throws Exception {
        Files.writeString(reports.resolve("TEST-" + name + ".xml"), xml);
    }
}
