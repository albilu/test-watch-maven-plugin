package com.example;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FooTest {
    @Test void testGreet() { assertEquals("foo", new Foo().greet()); }
}
