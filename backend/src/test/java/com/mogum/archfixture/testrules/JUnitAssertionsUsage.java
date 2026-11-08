package com.mogum.archfixture.testrules;

import org.junit.jupiter.api.Assertions;

/** 규칙 위반 전용이며 JUnit 테스트 메서드를 두지 않는다. */
public class JUnitAssertionsUsage {
    public void verify(Object value) {
        Assertions.assertNotNull(value);
    }
}
