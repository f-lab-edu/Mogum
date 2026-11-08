package com.mogum.archfixture.testrules;

import java.lang.reflect.Field;

public class AccessibleObjectUsage {
    public void open(Field field) {
        field.setAccessible(true);
    }
}
