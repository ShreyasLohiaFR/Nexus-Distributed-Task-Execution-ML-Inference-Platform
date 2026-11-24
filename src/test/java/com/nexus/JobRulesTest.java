package com.nexus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobRulesTest {
    @Test void textTasksAndRetryPolicy() {
        assertEquals(3, JobRules.wordCount("  hello\tworld\nagain "));
        assertEquals(0, JobRules.wordCount(" \t\n"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", JobRules.sha256("abc"));
        assertEquals(2, JobRules.retryDelaySeconds(1));
        assertEquals(32, JobRules.retryDelaySeconds(5));
        assertThrows(IllegalArgumentException.class, () -> JobRules.retryDelaySeconds(0));
    }
}
