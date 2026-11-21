package com.mogum.generation.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.mogum.course.domain.Level;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.Purpose;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CoursePlanFormatTest {
	private final CoursePlanFormat coursePlanFormat = new CoursePlanFormat();
	private final JsonMapper jsonMapper = new JsonMapper();

	@Test
	@DisplayName("새 코스는 입력과 수준만 받고 제목을 포함한 출력을 요청한다")
	void buildsNewCourse() {
		// given
		var input = new CoursePlanInput.NewCourse(input());

		// when
		var prompt = coursePlanFormat.prompt(input);

		// then
		var data = data(prompt);
		assertThat(data.properties()).extracting(entry -> entry.getKey())
			.containsExactlyInAnyOrder("topic", "purpose", "memo", "level");
		assertThat(data.path("topic").asString()).isEqualTo("자료 주제");
		assertThat(data.path("level").asString()).isEqualTo("기초부터");
		assertThat(prompt.system()).contains("코스 제목", "최대 10강", "핵심으로 좁혀", "안내하지 않는다");
	}

	@ParameterizedTest(name = "코스 순서 {0}, 다시 짜기 {1}, 수준 포함 {2}")
	@CsvSource({"1,false,true", "2,false,false", "3,false,false", "1,true,true", "2,true,false"})
	@DisplayName("로드맵 코스는 현재와 뒤 계획 및 바로 앞 실제 목록만 넣고 첫 코스에만 수준을 넣는다")
	void buildsRoadmapCourse(int position, boolean replan, boolean includesLevel) {
		// given
		var context = new CoursePlanInput.RoadmapContext(position, course("현재 계획"),
			List.of(lesson("바로 앞 실제 목록")), List.of(course("뒤 계획 하나"), course("뒤 계획 둘")));
		CoursePlanInput input = replan
			? new CoursePlanInput.ReplanCourse(input(), context, List.of(lesson("이전 목록")), "피드백 원문")
			: new CoursePlanInput.RoadmapCourse(input(), context);

		// when
		var prompt = coursePlanFormat.prompt(input);

		// then
		var data = data(prompt);
		assertThat(data.has("level")).isEqualTo(includesLevel);
		assertThat(data.path("coursePlan").path("title").asString()).isEqualTo("현재 계획");
		assertThat(data.path("followingCoursePlans")).hasSize(2);
		assertThat(data.path("followingCoursePlans").get(0).path("title").asString()).isEqualTo("뒤 계획 하나");
		assertThat(data.has("previousCourseLessons")).isEqualTo(position > 1);
		if (position > 1) {
			assertThat(data.path("previousCourseLessons")).hasSize(1);
			assertThat(data.path("previousCourseLessons").get(0).path("title").asString())
				.isEqualTo("바로 앞 실제 목록");
		}
		assertThat(data.has("previousLessons")).isEqualTo(replan);
		assertThat(data.has("feedback")).isEqualTo(replan);
		assertThat(data.properties()).extracting(entry -> entry.getKey())
			.doesNotContain("previousCoursePlans", "relatedConcepts", "mastery", "mastery_report");
		assertThat(prompt.system()).contains("계획의 개념은 모두",
			"더 잘게", "바로 앞", "뒤 코스", "다음 코스");
	}

	@Test
	@DisplayName("단일 코스 다시 짜기는 수준과 이전 목록 및 피드백만 더한다")
	void buildsSingleCourseReplan() {
		// given
		var input = new CoursePlanInput.ReplanCourse(input(), null, List.of(lesson("이전 목록")), "  빼 주세요\n그대로  ");

		// when
		var prompt = coursePlanFormat.prompt(input);

		// then
		var data = data(prompt);
		assertThat(data.has("level")).isTrue();
		assertThat(data.path("previousLessons").get(0).path("title").asString()).isEqualTo("이전 목록");
		assertThat(data.path("feedback").asString()).isEqualTo("  빼 주세요\n그대로  ");
		assertThat(data.has("coursePlan")).isFalse();
		assertThat(data.has("previousCourseLessons")).isFalse();
		assertThat(data.has("followingCoursePlans")).isFalse();
		assertThat(prompt.system()).contains("코스 제목을 출력하지 않는다", "피드백", "계획의 개념을 빼");
	}

	@ParameterizedTest(name = "빈 피드백 [{0}]")
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\n\t", "\u2003"})
	@DisplayName("빈 피드백은 다시 짜기에도 넣지 않는다")
	void omitsBlankFeedback(String feedback) {
		// given
		var input = new CoursePlanInput.ReplanCourse(input(), null, List.of(lesson("이전 목록")), feedback);

		// when
		var prompt = coursePlanFormat.prompt(input);

		// then
		assertThat(data(prompt).has("feedback")).isFalse();
		assertThat(data(prompt).has("previousLessons")).isTrue();
	}

	@ParameterizedTest(name = "포장 [{0}]")
	@ValueSource(strings = {"%s", "```json\n%s\n```", "앞 설명\n%s\n뒤 설명", "설명 {주석}\n%s\n끝"})
	@DisplayName("새 코스의 정상 JSON과 코드펜스 및 앞뒤 설명을 읽는다")
	void parsesNewCourse(String wrapper) {
		// given
		String text = wrapper.formatted(json("코스 제목", 1));

		// when
		var result = coursePlanFormat.parseNewCourse(text);

		// then
		assertThat(result).contains(new CoursePlan("코스 제목", List.of(lesson("강의 제목"))));
	}

	@Test
	@DisplayName("이어 붙인 답에서 문자열 안의 괄호와 이스케이프를 보존한다")
	void parsesConcatenatedReply() {
		// given
		String text = "```json\n{\"title\":\"코스 } \\\" 제목\",\"lessons\":"
			+ jsonMapper.writeValueAsString(List.of(lesson("강의 { 제목"))) + "}\n```";

		// when
		var result = coursePlanFormat.parseNewCourse(text);

		// then
		assertThat(result.orElseThrow().title()).isEqualTo("코스 } \" 제목");
		assertThat(result.orElseThrow().lessons().getFirst().title()).isEqualTo("강의 { 제목");
	}

	@Test
	@DisplayName("기존 코스의 결과는 제목 없이 강의만 읽고 알 수 없는 필드는 무시한다")
	void parsesExistingCourseWithoutTitle() {
		// given
		String text = "{\"lessons\":" + jsonMapper.writeValueAsString(List.of(lesson("강의 제목")))
			+ ",\"futureField\":true}";

		// when
		var result = coursePlanFormat.parseLessons(text);

		// then
		assertThat(result).contains(new CoursePlan(null, List.of(lesson("강의 제목"))));
	}

	@Test
	@DisplayName("기존 코스 결과에 온 제목은 사용하지 않는다")
	void ignoresUnexpectedCourseTitle() {
		// when
		var result = coursePlanFormat.parseLessons(json("바꾸면 안 되는 제목", 1));

		// then
		assertThat(result.orElseThrow().title()).isNull();
	}

	@ParameterizedTest(name = "강의 개수 {0}")
	@ValueSource(ints = {1, 10})
	@DisplayName("강의 수 양쪽 경계를 허용한다")
	void acceptsLessonCountBoundaries(int count) {
		// when
		var result = coursePlanFormat.parseNewCourse(json("코스 제목", count));

		// then
		assertThat(result.orElseThrow().lessons()).hasSize(count);
	}

	@ParameterizedTest(name = "100 코드 포인트 제목 [{0}]")
	@ValueSource(strings = {"가", "😀", "e\u0301"})
	@DisplayName("코스 제목은 이모지와 결합 문자를 코드 포인트로 세어 100자까지 허용한다")
	void acceptsOneHundredCodePoints(String unit) {
		// given
		String title = unit.repeat(100 / unit.codePointCount(0, unit.length()));

		// when
		var result = coursePlanFormat.parseNewCourse(json(title, 1));

		// then
		assertThat(result.orElseThrow().title()).isEqualTo(title);
	}

	@ParameterizedTest(name = "형식 불합격 {index}: {0}")
	@MethodSource("invalidReplies")
	@DisplayName("필수 값과 타입 및 개수와 길이가 틀리면 형식 불합격이다")
	void rejectsInvalidReplies(String text) {
		// when
		var result = coursePlanFormat.parseNewCourse(text);

		// then
		assertThat(result).isEmpty();
	}

	static Stream<String> invalidReplies() {
		String valid = json("코스 제목", 1);
		return Stream.of(null, "", "not json", "{}", "[]", "{\"title\":", json("제목", 0), json("제목", 11),
			json("가".repeat(101), 1), json("😀".repeat(101), 1), json("e\u0301".repeat(50) + "e", 1),
			valid.replace("\"코스 제목\"", "null"), valid.replace("\"코스 제목\"", "101"),
			valid.replace("\"코스 제목\"", "\" \\n\\t\""),
			valid.replace("\"강의 제목\"", "\"\""), valid.replace("\"강의 설명\"", "\"　\""),
			valid.replace("\"강의 설명\"", "false"), valid.replace("\"개념 이름\"", "null"),
			valid.replace("\"개념 이름\"", "\"\""), valid.replace("\"개념 정의\"", "\" \\t\""),
			valid.replace("\"개념 정의\"", "[]"), valid.replace("\"lessons\":[", "\"other\":["),
			valid.replace("\"concepts\":[", "\"other\":["),
			"{\"title\":\"제목\",\"lessons\":[null]}",
			"{\"title\":\"제목\",\"lessons\":{}}",
			valid.replace("[{\"name\":\"개념 이름\",\"definition\":\"개념 정의\"}]", "[]"),
			valid.replace("[{\"name\":\"개념 이름\",\"definition\":\"개념 정의\"}]", "[null]"));
	}

	@ParameterizedTest(name = "필수 문자열 {0}, 잘못된 값 {1}")
	@MethodSource("invalidRequiredStrings")
	@DisplayName("새 코스의 모든 필수 문자열은 누락과 null 및 빈 글과 다른 타입을 거부한다")
	void rejectsEveryInvalidRequiredString(String path, String invalid) {
		// given
		var root = (ObjectNode)jsonMapper.readTree(json("코스 제목", 1));
		ObjectNode target = switch (path) {
			case "title" -> root;
			case "lessonTitle", "description" -> (ObjectNode)root.path("lessons").get(0);
			default -> (ObjectNode)root.path("lessons").get(0).path("concepts").get(0);
		};
		String key = path.equals("lessonTitle") ? "title" : path;
		if (invalid.equals("missing"))
			target.remove(key);
		else
			target.set(key, jsonMapper.readTree(invalid));

		// when
		var result = coursePlanFormat.parseNewCourse(jsonMapper.writeValueAsString(root));

		// then
		assertThat(result).isEmpty();
	}

	static Stream<Arguments> invalidRequiredStrings() {
		return Stream.of("title", "lessonTitle", "description", "name", "definition")
			.flatMap(path -> Stream.of("missing", "null", "\"\"", "\" \\t\"", "\"\u00a0\"", "1", "{}", "[]")
				.map(invalid -> Arguments.of(path, invalid)));
	}

	@ParameterizedTest(name = "기존 목록 형식 불합격 {index}")
	@MethodSource("invalidLessonReplies")
	@DisplayName("제목을 받지 않는 기존 코스도 강의 수와 모든 강의 내용을 검사한다")
	void validatesExistingCourseLessons(String text) {
		// when
		var result = coursePlanFormat.parseLessons(text);

		// then
		assertThat(result).isEmpty();
	}

	static Stream<String> invalidLessonReplies() {
		return Stream.of(json("제목", 0), json("제목", 11), json("제목", 1).replace("강의 설명", " "),
			json("제목", 1).replace("개념 정의", " "), "{\"lessons\":null}", "{\"lessons\":[{}]}");
	}

	@Test
	@DisplayName("강의 제목과 설명 및 개념에는 길이 상한을 두지 않는다")
	void acceptsLongLessonText() {
		// given
		String longText = "가".repeat(500);
		String text = json("코스 제목", 1).replace("강의 제목", longText).replace("강의 설명", longText)
			.replace("개념 이름", longText).replace("개념 정의", longText);

		// when
		var result = coursePlanFormat.parseNewCourse(text);

		// then
		assertThat(result.orElseThrow().lessons().getFirst().title()).isEqualTo(longText);
	}

	static PlanningInput input() {
		return new PlanningInput("자료 주제", Level.BEGINNER, null, Purpose.WORK, null, "자료 메모");
	}

	static LessonOutline lesson(String title) {
		return new LessonOutline(title, "강의 설명", List.of(new PlannedConcept("개념 이름", "개념 정의")));
	}

	static CourseOutline course(String title) {
		return new CourseOutline(title, "코스 설명", List.of(new PlannedConcept("계획 이름", "계획 정의")));
	}

	static String json(String title, int count) {
		var mapper = new JsonMapper();
		return mapper.writeValueAsString(new CoursePlan(title, Collections.nCopies(count, lesson("강의 제목"))));
	}

	private JsonNode data(PlanPrompt prompt) {
		return jsonMapper.readTree(prompt.user().split("\n", 3)[1]);
	}
}
