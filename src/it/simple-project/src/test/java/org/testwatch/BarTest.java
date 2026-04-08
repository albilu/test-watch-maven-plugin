package org.testwatch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BarTest {
    @Test
    void testGreet() {
        assertEquals("bar", new Bar().greet());
    }
}
