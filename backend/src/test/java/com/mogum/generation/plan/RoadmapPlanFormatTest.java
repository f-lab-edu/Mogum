package com.mogum.generation.plan;

import static com.mogum.generation.plan.CoursePlanFormatTest.course;
import static com.mogum.generation.plan.CoursePlanFormatTest.input;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
import com.mogum.course.domain.Purpose;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RoadmapPlanFormatTest {
	private final RoadmapPlanFormat roadmapPlanFormat = new RoadmapPlanFormat();
	private final JsonMapper jsonMapper = new JsonMapper();

	@Test
	@DisplayName("새 로드맵은 수준과 주제 및 목적과 메모만 넣는다")
	void buildsNewRoadmap() {
		// given
		var input = new RoadmapPlanInput.NewRoadmap(input());

		// when
		var prompt = roadmapPlanFormat.prompt(input);

		// then
		var data = jsonMapper.readTree(prompt.user().split("\n", 3)[1]);
		assertThat(data.properties()).extracting(entry -> entry.getKey())
			.containsExactlyInAnyOrder("topic", "level", "purpose", "memo");
		assertThat(data.path("level").asString()).isEqualTo("기초부터");
		assertThat(prompt.system()).contains("코스 2개 이상", "상한은 없다", "좁은 주제", "한 단락", "한두 줄");
	}

	@Test
	@DisplayName("로드맵 다시 짜기는 이전 제목과 설명 및 모든 코스 계획과 피드백을 더한다")
	void buildsRoadmapReplan() {
		// given
		var previous = new RoadmapPlan("이전 제목", "이전 설명", List.of(course("계획 하나"), course("계획 둘")));
		var input = new RoadmapPlanInput.ReplanRoadmap(input(), previous, "  피드백\n원문  ");

		// when
		var prompt = roadmapPlanFormat.prompt(input);

		// then
		var data = jsonMapper.readTree(prompt.user().split("\n", 3)[1]);
		assertThat(data.path("previousPlan")).isEqualTo(jsonMapper.valueToTree(previous));
		assertThat(data.path("feedback").asString()).isEqualTo("  피드백\n원문  ");
		assertThat(data.has("level")).isTrue();
		assertThat(prompt.system()).contains("제목 · 설명 · 코스 계획을 모두 새로");
	}

	@ParameterizedTest(name = "다시 짜기={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("로드맵 관련 개념은 강의 목록과 같은 키와 판정 지시만 담고 원래 재료도 보존한다")
	void suppliesSharedRelatedConcepts(boolean replan) {
		// given
		var related = List.of(new RelatedConcept("변수", "값을 담는 이름", Judgment.WELL_KNOWN),
			new RelatedConcept("함수", "동작의 묶음", Judgment.SHAKY),
			new RelatedConcept("타입", "값의 종류", Judgment.NO_RECORD));
		RoadmapPlanInput original = roadmapInput(input(), replan);
		var input = original.withRelatedConcepts(related);
		var course = new CoursePlanFormat().prompt(new CoursePlanInput.NewCourse(input(), related));

		// when
		var prompt = roadmapPlanFormat.prompt(input);

		// then
		var data = jsonMapper.readTree(prompt.user().split("\n", 3)[1]);
		var concepts = data.path("relatedConcepts");
		assertThat(concepts).isEqualTo(jsonMapper.readTree(course.user().split("\n", 3)[1]).path("relatedConcepts"));
		assertThat(concepts).hasSize(3);
		assertThat(concepts.get(0).properties()).extracting(entry -> entry.getKey())
			.containsExactly("name", "definition", "instruction");
		assertThat(concepts.get(0).path("name").asString()).isEqualTo("변수");
		assertThat(concepts.get(0).path("definition").asString()).isEqualTo("값을 담는 이름");
		assertThat(concepts.get(0).path("instruction").asString()).isEqualTo("짧게 다룬다.");
		assertThat(concepts.get(1).path("instruction").asString()).isEqualTo("자세히 다룬다.");
		assertThat(concepts.get(2).path("instruction").asString()).isEqualTo("처음 배우는 것으로 보고 자세히 다룬다.");
		assertThat(prompt.user()).doesNotContain("WELL_KNOWN", "SHAKY", "NO_RECORD", "judgment", "0.9", "21");
		assertThat(data.path("topic").asString()).isEqualTo(input().topic());
		assertThat(data.has("previousPlan")).isEqualTo(replan);
		assertThat(data.has("feedback")).isEqualTo(replan);
		assertThat(original.relatedConcepts()).isEmpty();
	}

	@ParameterizedTest(name = "다시 짜기={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("빈 관련 개념은 생략하고 로드맵 배분과 피드백 우선 규칙은 시스템에 둔다")
	void omitsEmptyRelatedConceptsAndSuppliesRules(boolean replan) {
		// given
		var input = roadmapInput(input(), replan);

		// when
		var prompt = roadmapPlanFormat.prompt(input);

		// then
		assertThat(jsonMapper.readTree(prompt.user().split("\n", 3)[1]).has("relatedConcepts")).isFalse();
		assertThat(prompt.system()).contains("relatedConcepts", "이름 · 한 줄 정의 · instruction", "다른 개념과 묶어 적은 자리를",
			"「자세히 다룬다」인 개념에는 자리를 더", "판정으로 개념을 빼지 않는다", "주제와 관계없는 개념은 쓰지 않는다",
			"피드백은 어떤 것이든 숙련도로 정한 배분보다 앞선다", "코스 2개 이상", "상한은 없다");
		assertThat(prompt.system()).doesNotContain("mastery_report", "masteryReport");
	}

	@ParameterizedTest(name = "목적={0}, 다시 짜기={1}")
	@CsvSource({"WORK,false", "INTERVIEW,false", "GENERAL,false", "OTHER,false",
		"WORK,true", "INTERVIEW,true", "GENERAL,true", "OTHER,true"})
	@DisplayName("새 로드맵과 다시 짜기의 질의는 새 코스와 같은 주제와 목적 문구 하나다")
	void sharesTopicPurposeQuery(Purpose purpose, boolean replan) {
		// given
		var planning = new PlanningInput("주제", Level.OTHER, "수준 표식", purpose, "기타 목적", "메모 표식");
		var input = roadmapInput(planning, replan);
		var data = jsonMapper.readTree(roadmapPlanFormat.prompt(input).user().split("\n", 3)[1]);

		// when
		var queries = input.queryTexts();

		// then
		assertThat(queries).containsExactly("주제\n" + data.path("purpose").asString());
		assertThat(queries).isEqualTo(new CoursePlanInput.NewCourse(planning).queryTexts());
	}

	@ParameterizedTest(name = "다시 짜기={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("로드맵 입력은 외부 관련 개념 목록을 복사한다")
	void copiesRelatedConcepts(boolean replan) {
		// given
		var related = new ArrayList<>(List.of(new RelatedConcept("개념", "정의", Judgment.NO_RECORD)));
		var input = roadmapInput(input(), replan);

		// when
		var enriched = input.withRelatedConcepts(related);

		// then
		related.clear();
		assertThat(enriched.relatedConcepts()).hasSize(1);
		assertThat(input.relatedConcepts()).isEmpty();
	}

	private RoadmapPlanInput roadmapInput(PlanningInput input, boolean replan) {
		return replan ? new RoadmapPlanInput.ReplanRoadmap(input,
			new RoadmapPlan("이전 제목", "이전 설명", List.of(course("코스 하나"), course("코스 둘"))), "피드백 원문")
			: new RoadmapPlanInput.NewRoadmap(input);
	}

	@ParameterizedTest(name = "빈 피드백 [{0}]")
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n", "\u2003"})
	@DisplayName("로드맵의 빈 피드백은 넣지 않고 이전 계획은 넣는다")
	void omitsBlankFeedback(String feedback) {
		// given
		var previous = new RoadmapPlan("이전 제목", "이전 설명", List.of(course("계획 하나"), course("계획 둘")));

		// when
		var prompt = roadmapPlanFormat.prompt(new RoadmapPlanInput.ReplanRoadmap(input(), previous, feedback));

		// then
		var data = jsonMapper.readTree(prompt.user().split("\n", 3)[1]);
		assertThat(data.has("feedback")).isFalse();
		assertThat(data.has("previousPlan")).isTrue();
	}

	@ParameterizedTest(name = "포장 [{0}]")
	@ValueSource(strings = {"%s", "```json\n%s\n```", "앞 설명\n%s\n뒤 설명"})
	@DisplayName("정상 로드맵과 코드펜스 및 앞뒤 설명을 읽는다")
	void parsesRoadmap(String wrapper) {
		// given
		var expected = new RoadmapPlan("로드맵 제목", "로드맵 설명", List.of(course("코스 제목"), course("코스 제목")));

		// when
		var result = roadmapPlanFormat.parse(wrapper.formatted(jsonMapper.writeValueAsString(expected)));

		// then
		assertThat(result).contains(expected);
	}

	@ParameterizedTest(name = "코스 개수 {0}")
	@ValueSource(ints = {2, 11, 100})
	@DisplayName("로드맵은 두 코스부터 허용하고 수의 상한을 두지 않는다")
	void acceptsUnboundedCourses(int count) {
		// when
		var result = roadmapPlanFormat.parse(json("로드맵 제목", "코스 제목", count));

		// then
		assertThat(result.orElseThrow().courses()).hasSize(count);
	}

	@ParameterizedTest(name = "100 코드 포인트 제목 [{0}]")
	@ValueSource(strings = {"가", "😀", "e\u0301"})
	@DisplayName("로드맵과 계획 코스 제목은 코드 포인트 100자까지 허용한다")
	void acceptsTitleBoundaries(String unit) {
		// given
		String title = unit.repeat(100 / unit.codePointCount(0, unit.length()));

		// when
		var result = roadmapPlanFormat.parse(json(title, title, 2));

		// then
		assertThat(result.orElseThrow().title()).isEqualTo(title);
		assertThat(result.orElseThrow().courses().getFirst().title()).isEqualTo(title);
	}

	@ParameterizedTest(name = "형식 불합격 {index}: {0}")
	@MethodSource("invalidReplies")
	@DisplayName("로드맵과 코스의 필수 글과 개념 및 타입과 제목 길이를 검사한다")
	void rejectsInvalidReplies(String text) {
		// when
		var result = roadmapPlanFormat.parse(text);

		// then
		assertThat(result).isEmpty();
	}

	static Stream<String> invalidReplies() {
		String valid = json("로드맵 제목", "코스 제목", 2);
		return Stream.of(null, "", "broken json", "{}", "[]", json("제목", "제목", 0), json("제목", "제목", 1),
			json("😀".repeat(101), "제목", 2), json("제목", "😀".repeat(101), 2),
			json("e\u0301".repeat(50) + "e", "제목", 2),
			json("제목", "e\u0301".repeat(50) + "e", 2),
			valid.replace("\"로드맵 제목\"", "\" \\t\""),
			valid.replace("\"로드맵 제목\"", "null"), valid.replace("\"코스 제목\"", "\"\""),
			valid.replace("\"로드맵 설명\"", "\"　\""), valid.replace("\"로드맵 설명\"", "null"),
			valid.replace("\"코스 설명\"", "\" \\n\""), valid.replace("\"코스 설명\"", "123"),
			valid.replace("\"계획 이름\"", "\"\""), valid.replace("\"계획 정의\"", "null"),
			valid.replace("\"계획 정의\"", "\" \\t\""), valid.replace("\"계획 이름\"", "false"),
			valid.replace("\"courses\":[", "\"other\":["),
			valid.replace("\"concepts\":[", "\"other\":["),
			valid.replace("[{\"name\":\"계획 이름\",\"definition\":\"계획 정의\"}]", "[]"),
			valid.replace("[{\"name\":\"계획 이름\",\"definition\":\"계획 정의\"}]", "[null]"),
			"{\"title\":\"제목\",\"description\":\"설명\",\"courses\":[null,null]}");
	}

	@ParameterizedTest(name = "필수 문자열 {0}, 잘못된 값 {1}")
	@MethodSource("invalidRequiredStrings")
	@DisplayName("로드맵과 계획 코스의 모든 필수 문자열은 누락과 빈 글 및 다른 타입을 거부한다")
	void rejectsEveryInvalidRequiredString(String path, String invalid) {
		// given
		var root = (ObjectNode)jsonMapper.readTree(json("로드맵 제목", "코스 제목", 2));
		ObjectNode target = switch (path) {
			case "title", "description" -> root;
			case "courseTitle", "courseDescription" -> (ObjectNode)root.path("courses").get(1);
			default -> (ObjectNode)root.path("courses").get(1).path("concepts").get(0);
		};
		String key = switch (path) {
			case "courseTitle" -> "title";
			case "courseDescription" -> "description";
			default -> path;
		};
		if (invalid.equals("missing")) {
			target.remove(key);
		} else {
			target.set(key, jsonMapper.readTree(invalid));
		}

		// when
		var result = roadmapPlanFormat.parse(jsonMapper.writeValueAsString(root));

		// then
		assertThat(result).isEmpty();
	}

	static Stream<Arguments> invalidRequiredStrings() {
		return Stream.of("title", "description", "courseTitle", "courseDescription", "name", "definition")
			.flatMap(path -> Stream.of("missing", "null", "\"\"", "\" \\n\"", "\"\u00a0\"", "1", "{}", "[]")
				.map(invalid -> Arguments.of(path, invalid)));
	}

	@Test
	@DisplayName("로드맵 설명과 코스 설명 및 개념에는 길이 상한을 두지 않는다")
	void acceptsLongDescriptionsAndConcepts() {
		// given
		String longText = "가".repeat(500);
		String text = json("로드맵 제목", "코스 제목", 2).replace("로드맵 설명", longText)
			.replace("코스 설명", longText).replace("계획 이름", longText).replace("계획 정의", longText);

		// when
		var result = roadmapPlanFormat.parse(text);

		// then
		assertThat(result.orElseThrow().description()).isEqualTo(longText);
		assertThat(result.orElseThrow().courses().getFirst().concepts().getFirst().definition()).isEqualTo(longText);
	}

	private static String json(String title, String courseTitle, int count) {
		return new JsonMapper().writeValueAsString(
			new RoadmapPlan(title, "로드맵 설명", Collections.nCopies(count, course(courseTitle))));
	}
}
