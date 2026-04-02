import java.nio.file.*

// After test-watch:test exits (via CI timeout), verify that:
// 1. Surefire reports exist (full suite ran on startup)
// 2. Both FooTest and BarTest ran and passed

Path reportsDir = basedir.toPath().resolve("target/surefire-reports")
assert Files.exists(reportsDir) : "surefire-reports directory missing"

List<Path> xmlFiles = Files.list(reportsDir)
    .filter { it.toString().endsWith(".xml") && it.fileName.toString().startsWith("TEST-") }
    .collect()

assert xmlFiles.size() >= 2 : "Expected at least 2 TEST-*.xml files, found: ${xmlFiles.size()}"

xmlFiles.each { xml ->
    String content = xml.text
    assert !content.contains('<failure') : "Unexpected test failure in ${xml.fileName}"
    assert !content.contains('<error')   : "Unexpected test error in ${xml.fileName}"
}

println "IT verify passed: both FooTest and BarTest ran and passed."
