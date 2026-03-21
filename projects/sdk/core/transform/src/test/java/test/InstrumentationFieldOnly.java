package test;

public class InstrumentationFieldOnly {
    private TestInstrumentation instrumentation;

    int keep(int value) {
        try {
            return value + 1;
        } catch (Throwable throwable) {
            return value;
        }
    }
}
