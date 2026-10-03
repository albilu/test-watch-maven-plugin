import javax.xml.parsers.DocumentBuilderFactory

// One aggregator invocation must run and summarize both selected modules.
def factory = DocumentBuilderFactory.newInstance()
factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
['module-a': 'org.testwatch.a.AlphaTest', 'module-b': 'org.testwatch.b.BetaTest'].each { module, name ->
    File report = new File(basedir, "${module}/target/surefire-reports/TEST-${name}.xml")
    assert report.isFile() : "Missing report for ${module}"
    def suite = factory.newDocumentBuilder().parse(report).documentElement
    assert suite.getAttribute('tests').toInteger() == 1
    assert suite.getAttribute('failures').toInteger() == 0
    assert suite.getAttribute('errors').toInteger() == 0
}
assert new File(basedir, 'build.log').text.contains('PASS  Tests: 2 passed')
println 'IT verify passed: both modules executed and their results were aggregated.'
