When multiple run are triggered (runs are executed sequentially) [Image 1] this is not very good as user doesn't have insight of the queue list
There should be two pannels: one for the test execution output and another one displaying the test-watch queue and the global result summary (mvn or sufire result):

> Run 2: Test3.java (queued)
> Run 1: Test1.java,Test2.java ... (running)

FAIL Tests: 23 failed, 2 errors, 5 skipped, 2152 passed

[test-watch] Watching for changes. [r] rerun all [f] rerun failed [q] quit
