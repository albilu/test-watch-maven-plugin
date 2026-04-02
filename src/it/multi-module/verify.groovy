import java.nio.file.*

// After test-watch:test exits (via CI timeout) in module-a, verify that:
// 1. Surefire reports exist in module-a (suite ran on startup)
// 2. AlphaTest ran and passed in module-a

Path reportsDir = basedir.toPath().resolve("module-a/target/surefire-reports")
assert Files.exists(reportsDir) : "module-a surefire-reports directory missing"

List<Path> xmlFiles = Files.list(reportsDir)
    .filter { it.toString().endsWith(".xml") && it.fileName.toString().startsWith("TEST-") }
    .collect()

assert xmlFiles.size() >= 1 : "Expected at least 1 TEST-*.xml in module-a, found: ${xmlFiles.size()}"

xmlFiles.each { xml ->
    String content = xml.text
    assert !content.contains('<failure') : "Unexpected test failure in ${xml.fileName}"
    assert !content.contains('<error')   : "Unexpected test error in ${xml.fileName}"
}

println "IT verify passed: AlphaTest ran and passed in module-a."
