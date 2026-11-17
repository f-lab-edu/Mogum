package com.mogum.generation.plan;

import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PlanJsonTest {
    @ParameterizedTest(name = "JSON 포장 [{0}]")
    @ValueSource(strings = {"%s", "```json\n%s\n```", "설명 {}\n%s\n뒤 설명"})
    @DisplayName("최상위 JSON 객체를 찾아 문자열 속 괄호와 이스케이프를 보존한다")
    void extractsJsonObject(String wrapper) {
        // given
        String json = "{\"title\":\"괄호 } { \\\" \\\\ 끝\",\"lessons\":[]}";

        // when
        var result = PlanJson.read(wrapper.formatted(json));

        // then
        assertThat(result.orElseThrow().path("title").asString()).isEqualTo("괄호 } { \" \\ 끝");
    }

    @Test
    @DisplayName("서로 다른 계획 JSON 두 개는 하나의 답으로 받지 않는다")
    void rejectsMultiplePlans() {
        // given
        String text = CoursePlanFormatTest.json("제목", 1);

        // when
        var result = PlanJson.read(text + "\n" + text);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("잘못된 답의 원문과 프롬프트의 사용자 원문을 로그에 남기지 않는다")
    void keepsRawTextOutOfLogs(CapturedOutput output) {
        // given
        String marker = "private-plan-marker-9234";
        var input = new PlanningInput(marker, Level.OTHER, marker,
                Purpose.OTHER, marker, marker);
        new CoursePlanFormat().prompt(new CoursePlanInput.NewCourse(input));

        // when
        var result = PlanJson.read("{\"lessons\": [" + marker + " ]}");

        // then
        assertThat(result).isEmpty();
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @ParameterizedTest(name = "읽을 수 없는 답 [{0}]")
    @ValueSource(strings = {"{\"title\":\"끝나지 않음", "설명 {broken} 끝", "[{}]", "{} {}"})
    @DisplayName("미완성 JSON과 잘못된 JSON 및 배열과 여러 객체는 거부한다")
    void rejectsUnreadableOrMultipleObjects(String text) {
        // when
        var result = PlanJson.read(text);

        // then
        assertThat(result).isEmpty();
    }
}
