import javax.xml.parsers.DocumentBuilderFactory

def factory = DocumentBuilderFactory.newInstance()
factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
File report = new File(basedir, 'target/surefire-reports/TEST-org.testwatch.ExecutionPolicyTest.xml')
assert report.isFile()
def suite = factory.newDocumentBuilder().parse(report).documentElement
assert suite.getAttribute('tests').toInteger() == 4
assert suite.getAttribute('failures').toInteger() == 0
assert suite.getAttribute('errors').toInteger() == 0
def properties = suite.getElementsByTagName('property')
for (int i = 0; i < properties.length; i++) {
    assert !(properties.item(i).getAttribute('name') in [
        'parallel', 'useUnlimitedThreads', 'junit.jupiter.execution.parallel.enabled',
        'junit.jupiter.execution.parallel.mode.default', 'junit.jupiter.execution.parallel.mode.classes.default'
    ]) : 'The watcher injected an unrequested execution setting'
}
assert new File(basedir, 'build.log').text.contains('PASS  Tests: 4 passed')
println 'IT verify passed: default watch execution preserves the project execution policy.'
