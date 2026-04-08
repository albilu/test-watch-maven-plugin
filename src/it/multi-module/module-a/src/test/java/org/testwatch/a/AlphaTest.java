package org.testwatch.a;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlphaTest {
    @Test
    void testName() {
        assertEquals("alpha", new Alpha().name());
    }
}
