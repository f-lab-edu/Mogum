package com.mogum.course.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialTest {
    @Test
    @DisplayName("강의 자료는 강의와 마크다운 본문을 담는다")
    void writesMaterialForLesson() {
        // given
        var lesson = LessonFixtures.lesson(CourseFixtures.single(8L), 1);

        // when
        var material = Material.write(lesson, "# 변수\n값을 담는 이름이에요.");

        // then
        assertThat(material.getId()).isNull();
        assertThat(material.getCreatedAt()).isNull();
        assertThat(material.getUpdatedAt()).isNull();
        assertThat(material.getLesson()).isSameAs(lesson);
        assertThat(material.getBody()).isEqualTo("# 변수\n값을 담는 이름이에요.");
    }
}

