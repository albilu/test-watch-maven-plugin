package com.example.fixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
// This class references Foo but NOT Bar — verifies dependency graph accuracy
class FooTest {
    @Test void testGreet() { assertEquals("hello", new Foo().greet()); }
}
