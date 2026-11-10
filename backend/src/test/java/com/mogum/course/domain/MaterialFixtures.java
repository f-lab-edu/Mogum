package com.mogum.course.domain;

public final class MaterialFixtures {
    private MaterialFixtures() { }

    public static Material material(Lesson lesson, String body) {
        return Material.write(lesson, body);
    }
}

