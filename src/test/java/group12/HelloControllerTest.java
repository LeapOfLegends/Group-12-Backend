package group12;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HelloControllerTest {
    @Test
    void helloShouldReturnHelloWorld() {
        HelloController controller = new HelloController();

        assertEquals("Hello, World!", controller.hello());
    }
}
