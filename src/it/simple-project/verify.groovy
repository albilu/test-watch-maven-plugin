import javax.xml.parsers.DocumentBuilderFactory

// Parallel Surefire runs can group cases from different classes into one report.
def factory = DocumentBuilderFactory.newInstance()
factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
def names = []
int total = 0
new File(basedir, 'target/surefire-reports').eachFileMatch(~/TEST-.*\.xml/) { report ->
    def suite = factory.newDocumentBuilder().parse(report).documentElement
    total += suite.getAttribute('tests').toInteger()
    assert suite.getAttribute('failures').toInteger() == 0
    assert suite.getAttribute('errors').toInteger() == 0
    def cases = suite.getElementsByTagName('testcase')
    for (int i = 0; i < cases.length; i++) names.add(cases.item(i).getAttribute('classname'))
}
assert total == 2
assert names.sort() == ['org.testwatch.BarTest', 'org.testwatch.FooTest']
assert new File(basedir, 'build.log').text.contains('PASS  Tests: 2 passed')
println 'IT verify passed: both tests executed and the latest-run summary is correct.'
