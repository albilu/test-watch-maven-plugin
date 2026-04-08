package org.testwatch.b;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BetaTest {
    @Test
    void testName() {
        assertEquals("beta", new Beta().name());
    }
}
