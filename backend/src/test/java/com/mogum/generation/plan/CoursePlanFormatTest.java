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

import com.mogum.card.domain.Judgment;
import com.mogum.card.service.ConceptLookupService.RelatedConcept;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.MasteryReport;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.Purpose;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CoursePlanFormatTest {
	private final CoursePlanFormat coursePlanFormat = new CoursePlanFormat();
	private final JsonMapper jsonMapper = new JsonMapper();

	@ParameterizedTest(name = "새 코스={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("반영 내역의 이름은 강의 개념과 맞추거나 중복을 고치지 않고 그대로 읽는다")
	void parsesMasteryReportWithoutMatchingNames(boolean newCourse) {
		// given
		var root = (ObjectNode)jsonMapper.readTree(json("코스 제목", 1));
		root.set("masteryReport", jsonMapper.readTree(
			"{\"shortened\":[\"없는 개념\",\"없는 개념\",\"\"],\"revisited\":[\"다시 짚음\"],\"future\":true}"));

		// when
		var result = newCourse ? coursePlanFormat.parseNewCourse(root.toString())
			: coursePlanFormat.parseLessons(root.toString());

		// then
		assertThat(result.orElseThrow().masteryReport()).isEqualTo(
			new MasteryReport(List.of("없는 개념", "없는 개념", ""), List.of("다시 짚음")));
		assertThat(result.orElseThrow().lessons()).containsExactly(lesson("강의 제목"));
	}

	@ParameterizedTest(name = "새 코스={0}, 내역={1}")
	@MethodSource("invalidMasteryReports")
	@DisplayName("반영 내역이 없거나 모양이 틀리면 강의 목록을 받고 두 목록만 비운다")
	void acceptsLessonsWithInvalidMasteryReport(boolean newCourse, String report) {
		// given
		var root = (ObjectNode)jsonMapper.readTree(json("코스 제목", 1));
		root.remove("masteryReport");
		if (report != null) {
			root.set("masteryReport", jsonMapper.readTree(report));
		}

		// when
		var result = newCourse ? coursePlanFormat.parseNewCourse(root.toString())
			: coursePlanFormat.parseLessons(root.toString());

		// then
		assertThat(result.orElseThrow().masteryReport()).isEqualTo(new MasteryReport(List.of(), List.of()));
		assertThat(result.orElseThrow().lessons()).containsExactly(lesson("강의 제목"));
	}

	static Stream<Arguments> invalidMasteryReports() {
		return Stream.of(true, false).flatMap(newCourse -> Stream.of(
			(String)null, "null", "[]", "1", "true", "\"내역\"", "{}",
			"{\"shortened\":[]}", "{\"revisited\":[]}",
			"{\"shortened\":[],\"revisited\":null}",
			"{\"shortened\":[],\"revisited\":\"개념\"}",
			"{\"shortened\":{},\"revisited\":[]}",
			"{\"shortened\":[\"정상\",1],\"revisited\":[]}",
			"{\"shortened\":[],\"revisited\":[null]}",
			"{\"shortened\":[true],\"revisited\":[]}",
			"{\"shortened\":[],\"revisited\":[{}]}",
			"{\"shortened\":[[]],\"revisited\":[]}")
			.map(report -> Arguments.of(newCourse, report)));
	}

	@ParameterizedTest(name = "새 코스={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("정상 반영 내역이 있어도 잘못된 강의 목록은 불합격이다")
	void rejectsInvalidLessonsWithValidMasteryReport(boolean newCourse) {
		// given
		String text = "{\"title\":\"코스\",\"lessons\":[],"
			+ "\"masteryReport\":{\"shortened\":[],\"revisited\":[]}}";

		// when
		var result = newCourse ? coursePlanFormat.parseNewCourse(text) : coursePlanFormat.parseLessons(text);

		// then
		assertThat(result).isEmpty();
	}

	@ParameterizedTest(name = "새 코스={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("강의 목록 프롬프트는 반영 내역의 뜻과 출력 모양 및 빈 목록 규칙을 지시한다")
	void requestsMasteryReport(boolean newCourse) {
		// given
		CoursePlanInput input = newCourse ? new CoursePlanInput.NewCourse(input())
			: new CoursePlanInput.ReplanCourse(input(), null, List.of(), null);

		// when
		var prompt = coursePlanFormat.prompt(input);

		// then
		assertThat(prompt.system()).contains("masteryReport", "shortened", "revisited", "빈 목록", "관련 개념");
	}

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

	@Test
	@DisplayName("관련 개념은 이름과 정의 및 공통 지시만 싣고 판정과 수치는 싣지 않는다")
	void suppliesRelatedConceptInstructions() {
		// given
		var related = List.of(new RelatedConcept("변수", "값을 담는 이름", Judgment.WELL_KNOWN),
			new RelatedConcept("함수", "동작의 묶음", Judgment.SHAKY),
			new RelatedConcept("타입", "값의 종류", Judgment.NO_RECORD));
		var input = new CoursePlanInput.NewCourse(input()).withRelatedConcepts(related);

		// when
		var prompt = coursePlanFormat.prompt(input);

		// then
		var concepts = data(prompt).path("relatedConcepts");
		assertThat(concepts).hasSize(3);
		assertThat(concepts.get(0).properties()).extracting(entry -> entry.getKey())
			.containsExactly("name", "definition", "instruction");
		assertThat(concepts.get(0).path("name").asString()).isEqualTo("변수");
		assertThat(concepts.get(0).path("definition").asString()).isEqualTo("값을 담는 이름");
		assertThat(concepts.get(0).path("instruction").asString()).isEqualTo("짧게 다룬다.");
		assertThat(concepts.get(1).path("instruction").asString()).isEqualTo("전에 배웠지만 흔들린다. 자세히 다룬다.");
		assertThat(concepts.get(2).path("instruction").asString()).isEqualTo("처음 배우는 것으로 보고 자세히 다룬다.");
		assertThat(prompt.user()).doesNotContain("WELL_KNOWN", "SHAKY", "NO_RECORD", "judgment", "0.9", "21");
	}

	@Test
	@DisplayName("빈 관련 개념 칸은 생략하고 배분과 다시 짚기 및 피드백 우선 규칙을 시스템에 둔다")
	void suppliesMasteryRulesWithoutEmptyRelatedConcepts() {
		// given
		var input = new CoursePlanInput.NewCourse(input());

		// when
		var prompt = coursePlanFormat.prompt(input);

		// then
		assertThat(data(prompt).has("relatedConcepts")).isFalse();
		assertThat(prompt.system()).contains("10강 중 최대 2강", "주제와 관계없는 개념은 쓰지 않는다",
			"피드백은 어떤 것이든 숙련도로 정한 배분보다 앞선다", "판정으로 개념을 빼지 않는다",
			"다른 개념과 한 강의에 묶어", "강의를 따로",
			"지시가 「전에 배웠지만 흔들린다」로 시작하는 관련 개념을 다시 짚는 강의를 넣을 수 있다. 로드맵 계획에 없는 개념이어도 된다.",
			"revisited는 지시가 「전에 배웠지만 흔들린다」로 시작하는 관련 개념 가운데 다시 짚는 강의에서 다룬 개념이다.");
	}

	@ParameterizedTest(name = "목적={0}")
	@org.junit.jupiter.params.provider.EnumSource(Purpose.class)
	@DisplayName("주제와 목적 질의는 프롬프트의 목적 문구와 같고 수준과 메모를 포함하지 않는다")
	void buildsQueryWithPromptPurpose(Purpose purpose) {
		// given
		var planning = new PlanningInput("주제", Level.OTHER, "수준 표식", purpose, "기타 목적 원문", "메모 표식");
		var input = new CoursePlanInput.NewCourse(planning);
		var purposeText = data(coursePlanFormat.prompt(input)).path("purpose").asString();

		// when
		var queries = input.queryTexts();

		// then
		assertThat(queries).containsExactly("주제\n" + purposeText);
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
		assertThat(result)
			.contains(new CoursePlan("코스 제목", List.of(lesson("강의 제목")), new MasteryReport(List.of(), List.of())));
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
		assertThat(result)
			.contains(new CoursePlan(null, List.of(lesson("강의 제목")), new MasteryReport(List.of(), List.of())));
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
		if (invalid.equals("missing")) {
			target.remove(key);
		} else {
			target.set(key, jsonMapper.readTree(invalid));
		}

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
		return mapper.writeValueAsString(new CoursePlan(title, Collections.nCopies(count, lesson("강의 제목")),
			new MasteryReport(List.of(), List.of())));
	}

	private JsonNode data(PlanPrompt prompt) {
		return jsonMapper.readTree(prompt.user().split("\n", 3)[1]);
	}
}
