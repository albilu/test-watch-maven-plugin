package org.testwatch;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionPolicyTest {
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    @RepeatedTest(4)
    void preservesSequentialExecution() throws Exception {
        int simultaneous = ACTIVE.incrementAndGet();
        try {
            Thread.sleep(100);
            assertEquals(1, simultaneous, "The watcher must not enable parallel test methods by default");
        } finally {
            ACTIVE.decrementAndGet();
        }
    }
}
