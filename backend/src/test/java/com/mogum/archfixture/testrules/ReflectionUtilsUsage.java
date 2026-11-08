package com.mogum.archfixture.testrules;

import org.springframework.test.util.ReflectionTestUtils;

public class ReflectionUtilsUsage {
    public void changeId(Object entity) {
        ReflectionTestUtils.setField(entity, "id", 1L);
    }
}
