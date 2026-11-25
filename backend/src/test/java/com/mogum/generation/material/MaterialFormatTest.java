package com.mogum.generation.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.mogum.card.domain.Judgment;
import com.mogum.course.domain.PlannedConcept;
import com.mogum.course.domain.Purpose;

import tools.jackson.databind.json.JsonMapper;

class MaterialFormatTest {
	private final MaterialFormat materialFormat = new MaterialFormat();
	private static final String CARD = """
		{"question":"질문?", "answer":"답", "concepts":[{"name":"개념","definition":"정의"}]}
		""".strip();

	@ParameterizedTest(name = "구분 줄={0}")
	@MethodSource("paddedSeparators")
	@DisplayName("구분 줄 앞뒤의 스페이스와 탭과 CRLF를 받는다")
	void acceptsPaddedSeparator(String separator) {
		// given
		String output = "본문\n" + separator + "{\"cards\":[" + CARD + "]}";
		// when
		var parsed = materialFormat.parse(output);
		// then
		assertThat(parsed).isPresent();
		assertThat(parsed.orElseThrow().body()).isEqualTo("본문");
		assertThat(parsed.orElseThrow().cards()).hasSize(1);
	}

	static Stream<String> paddedSeparators() {
		return Stream.of("<!-- MOGUM_CARDS --> \n", "<!-- MOGUM_CARDS -->\t\n",
			"  <!-- MOGUM_CARDS -->\n", "\t<!-- MOGUM_CARDS -->\t \n",
			" \t<!-- MOGUM_CARDS --> \t\r\n");
	}

	@ParameterizedTest(name = "펜스={0}")
	@ValueSource(strings = {"```json\n", "```JSON\n", "```\n", "```JsOn\r\n"})
	@DisplayName("카드 JSON을 감싼 펜스 하나의 안쪽만 읽고 본문의 코드와 그림과 수식을 보존한다")
	void unwrapsCardFenceAndPreservesBody(String opening) {
		// given
		String body = "# 본문\n```java\nString value = \"안녕\";\n```\n"
			+ "```mermaid\nflowchart LR\n  A --> B\n```\n$$\\frac{1}{2}$$";
		String output = " \n" + body + "\n \t<!-- MOGUM_CARDS -->\t\r\n \t"
			+ opening + "{\"cards\":[" + CARD + "]}\r\n```\n\t ";
		// when
		var parsed = materialFormat.parse(output);
		// then
		assertThat(parsed).isPresent();
		assertThat(parsed.orElseThrow().body()).isEqualTo(body);
		assertThat(parsed.orElseThrow().cards()).containsExactly(new MaterialContent.Card("질문?", "답",
			List.of(new PlannedConcept("개념", "정의"))));
	}

	@ParameterizedTest(name = "불합격 펜스={0}")
	@MethodSource("invalidFencedCards")
	@DisplayName("펜스 바깥 글과 닫히지 않거나 여러 개인 펜스와 펜스 안의 잘못된 JSON은 거절한다")
	void rejectsInvalidFencedCards(String cards) {
		// given
		String output = "본문\n<!-- MOGUM_CARDS -->\n" + cards;
		// when
		var parsed = materialFormat.parse(output);
		// then
		assertThat(parsed).isEmpty();
	}

	static Stream<String> invalidFencedCards() {
		String json = "{\"cards\":[" + CARD + "]}";
		String fenced = "```json\n" + json + "\n```";
		return Stream.of("앞 글\n" + fenced, fenced + "\n뒤 글", "```json\n" + json,
			fenced + "\n" + fenced, "```\n" + fenced + "\n```", "```java\n" + json + "\n```",
			"```json\n" + json + " {}\n```", "```json\n" + json + " 뒤 글\n```",
			"```json\n{\"cards\":[]}\n```", "```json\n{\"cards\":["
				+ CARD.replace("질문?", " ") + "]}\n```",
			"```json\n" + json + "\n```\n \t<!-- MOGUM_CARDS --> \t\n{}");
	}

	@ParameterizedTest(name = "같은 줄={0}")
	@ValueSource(strings = {"설명 <!-- MOGUM_CARDS -->", "<!-- MOGUM_CARDS --> 설명",
		"설명 <!-- MOGUM_CARDS --> 설명"})
	@DisplayName("다른 글과 같은 줄에 있는 표식만으로는 본문과 카드를 나누지 않는다")
	void rejectsInlineSeparator(String line) {
		// given
		String output = "본문\n" + line + "\n{\"cards\":[" + CARD + "]}";
		// when
		var parsed = materialFormat.parse(output);
		// then
		assertThat(parsed).isEmpty();
	}

	@Test
	@DisplayName("본문의 같은 줄 표식은 그대로 남기고 혼자 있는 구분 줄에서만 나눈다")
	void preservesInlineMarkerInBody() {
		// given
		String body = "본문의 <!-- MOGUM_CARDS --> 표식";
		String output = body + "\n<!-- MOGUM_CARDS -->\n{\"cards\":[" + CARD + "]}";
		// when
		var parsed = materialFormat.parse(output);
		// then
		assertThat(parsed.orElseThrow().body()).isEqualTo(body);
	}

	@ParameterizedTest(name = "이어 쓰기 위치={0}")
	@ValueSource(strings = {"MOGUM_CARDS", "```json", "definition"})
	@DisplayName("구분 줄이나 펜스나 JSON 중간에서 이어 붙인 전체 출력에도 같은 허용 규칙을 쓴다")
	void parsesConcatenatedToleratedOutput(String fragment) {
		// given
		String output = "본문\n \t<!-- MOGUM_CARDS --> \t\r\n```json\n{\"cards\":[" + CARD + "]}\n```";
		int cut = output.indexOf(fragment) + fragment.length() / 2;
		String first = output.substring(0, cut);
		String continuation = output.substring(cut);
		// when
		var parsed = materialFormat.parse(first + continuation);
		// then
		assertThat(parsed).isPresent();
		assertThat(parsed.orElseThrow().body()).isEqualTo("본문");
		assertThat(parsed.orElseThrow().cards()).hasSize(1);
	}

	@Test
	@DisplayName("코드와 수식과 Mermaid와 표가 든 본문을 이스케이프 없이 그대로 읽는다")
	void preservesMarkdownAndParsesConcatenatedOutput() {
		// given
		// @checkstyle:off — 텍스트 블록의 공백 들여쓰기를 보존한다.
		String body = """
			# 이번 강의에서 배울 것
			`이름`과 "값"
			```java
			String value = "안녕";
			```
			$$\\frac{1}{2} \\\\ x$$
			```mermaid
			flowchart LR
			  A --> B
			```
			| 이름 | 뜻 |
			| --- | --- |
			| 값 | 자료 |
			""".strip();
		// @checkstyle:on
		String output = body + "\n<!-- MOGUM_CARDS -->\n{\"cards\":[" + CARD + "]}";
		int cut = output.indexOf("definition") + 4;
		// when
		var parsed = materialFormat.parse(output.substring(0, cut) + output.substring(cut));
		// then
		assertThat(parsed).isPresent();
		assertThat(parsed.orElseThrow().body()).isEqualTo(body);
		assertThat(parsed.orElseThrow().cards()).singleElement().satisfies(card -> {
			assertThat(card.question()).isEqualTo("질문?");
			assertThat(card.answer()).isEqualTo("답");
			assertThat(card.concepts()).containsExactly(new PlannedConcept("개념", "정의"));
		});
	}

	@Test
	@DisplayName("카드와 개념 수의 권장 범위를 넘거나 그림 문법이 깨져도 읽을 수 있으면 받는다")
	void acceptsCountsOutsidePromptRanges() {
		// given
		String concept = "{\"name\":\"개념\",\"definition\":\"정의\"}";
		String card = "{\"question\":\"질문\",\"answer\":\"답\",\"concepts\":["
			+ String.join(",", IntStream.range(0, 4).mapToObj(i -> concept).toList()) + "]}";
		String cards = String.join(",", IntStream.range(0, 7).mapToObj(i -> card).toList());
		// when
		var parsed = materialFormat.parse("```mermaid\nbroken\n```\n<!-- MOGUM_CARDS -->\n{\"cards\":[" + cards + "]}");
		// then
		assertThat(parsed.orElseThrow().cards()).hasSize(7).allSatisfy(c -> assertThat(c.concepts()).hasSize(4));
	}

	@ParameterizedTest(name = "불합격={0}")
	@MethodSource("invalidOutputs")
	@DisplayName("본문이나 카드의 필수 글과 개념이 없거나 JSON이 깨지면 불합격이다")
	void rejectsUnreadableOutput(String output) {
		// when
		var parsed = materialFormat.parse(output);
		// then
		assertThat(parsed).isEmpty();
	}

	static Stream<String> invalidOutputs() {
		String valid = "본문\n<!-- MOGUM_CARDS -->\n{\"cards\":[" + CARD + "]}";
		return Stream.of(null, "", "본문", valid.replace("본문", "　"), valid.replace(CARD, ""),
			valid.replace("질문?", " "), valid.replace("답", ""), valid.replace("개념", "　"),
			valid.replace("정의", ""), valid.replace("\"question\":\"질문?\"", "\"question\":null"),
			valid.replace("[{\"name\":\"개념\",\"definition\":\"정의\"}]", "[]"),
			valid.substring(0, valid.length() - 1), valid + " {}", valid.replace(CARD, "null"),
			valid.replace("\"cards\":[" + CARD + "]", "\"cards\":null"),
			valid.replace("\"cards\":[" + CARD + "]", "\"cards\":{}"),
			valid.replace("\"answer\":\"답\"", "\"answer\":3"),
			valid.replace("\"concepts\":[{\"name\":\"개념\",\"definition\":\"정의\"}]", "\"concepts\":null"),
			valid.replace("\"definition\":\"정의\"", "\"definition\":false"),
			valid + "\n<!-- MOGUM_CARDS -->\n{}");
	}

	@ParameterizedTest(name = "목적={0}")
	@CsvSource({"WORK,예제 · 실습 · 현업에서 주의할 점",
		"INTERVIEW,왜 그런가 · 무엇과 비교해 어떤가 · 면접 질문형 카드", "GENERAL,비유 · 흐름"})
	@DisplayName("고른 목적은 강의 목록과 같은 정해진 문구로 넘기고 빈 메모는 넣지 않는다")
	void rendersSelectedPurpose(Purpose purpose, String phrase) {
		// given
		var input = new MaterialInput("코스", "주제", new MaterialInput.LessonLine(1, "강의", "설명", List.of()),
			List.of(new MaterialInput.LessonSummary(1, "강의", "설명", true)), purpose, null, null);
		// when
		var prompt = materialFormat.prompt(input);
		// then
		assertThat(prompt.user()).contains("\"purpose\":\"" + phrase + "\"").doesNotContain("memo", "level",
			"feedback");
	}

	@Test
	@DisplayName("프롬프트는 허용된 재료와 목적 원문만 넣고 고정 뼈대와 카드 규칙을 지시한다")
	void rendersPromptWithPurposeAndRules() {
		// given
		var input = new MaterialInput("코스", "주제", new MaterialInput.LessonLine(2, "강의", "설명",
			List.of(new PlannedConcept("개념", "정의"))),
			List.of(new MaterialInput.LessonSummary(1, "앞", "앞 설명", false),
				new MaterialInput.LessonSummary(2, "강의", "설명", true)),
			Purpose.OTHER, "목적 원문", "메모 원문");
		// when
		var prompt = materialFormat.prompt(input);
		// then
		assertThat(prompt.user()).contains("코스", "주제", "개념", "정의", "앞 설명", "\"current\":true", "목적 원문", "메모 원문")
			.doesNotContain("level", "feedback", "mastery");
		assertThat(prompt.system()).contains("이번 강의에서 배울 것", "개념 설명", "정리", "3~6", "1~3", "strict", "KaTeX",
			"현업에서 주의할 점", "면접에서 이렇게 물어요", "과제", "<!-- MOGUM_CARDS -->");
	}

	@Test
	@DisplayName("직렬화한 강의 자료의 개념 키는 이름과 정의와 지시 순서다")
	void serializesConceptKeysInStableOrder() {
		// given
		var input = new MaterialInput("코스", "주제", new MaterialInput.LessonLine(1, "강의", "설명",
			List.of(new PlannedConcept("변수", "값을 담는 이름"), new PlannedConcept("함수", "동작의 묶음"))),
			List.of(), Purpose.GENERAL, null, null);

		// when
		var prompt = materialFormat.prompt(input);

		// then
		assertThat(prompt.user()).contains(
			"{\"name\":\"변수\",\"definition\":\"값을 담는 이름\",\"instruction\":\"처음 배우는 것으로 보고 자세히 다룬다.\"}",
			"{\"name\":\"함수\",\"definition\":\"동작의 묶음\",\"instruction\":\"처음 배우는 것으로 보고 자세히 다룬다.\"}");
	}

	@Test
	@DisplayName("모든 개념에 서로 다른 판정 지시를 붙이고 판정 이름이나 숙련도 숫자는 넘기지 않는다")
	void rendersInstructionsForEveryConceptWithoutJudgmentData() {
		// given
		var input = new MaterialInput("코스", "주제", new MaterialInput.LessonLine(1, "강의", "설명",
			List.of(new PlannedConcept("아는 개념", "아는 정의"), new PlannedConcept("흔들리는 개념", "흔들리는 정의"),
				new PlannedConcept("새 개념", "새 정의"))),
			List.of(), Purpose.GENERAL, null, null)
			.withJudgments(Map.of("아는 정의", Judgment.WELL_KNOWN, "흔들리는 정의", Judgment.SHAKY,
				"새 정의", Judgment.NO_RECORD));

		// when
		var prompt = materialFormat.prompt(input);

		// then
		var data = JsonMapper.builder().build().readTree(prompt.user()
			.replace("자료 JSON 시작", "").replace("자료 JSON 끝", "").strip());
		var concepts = data.path("lesson").path("concepts");
		assertThat(concepts.size()).isEqualTo(3);
		assertThat(concepts.get(0).path("instruction").asString()).isEqualTo("짧게 다룬다.");
		assertThat(concepts.get(1).path("instruction").asString()).isEqualTo("전에 배웠지만 흔들린다. 자세히 다룬다.");
		assertThat(concepts.get(2).path("instruction").asString()).isEqualTo("처음 배우는 것으로 보고 자세히 다룬다.");
		for (var concept : concepts) {
			assertThat(concept.propertyNames()).containsExactly("name", "definition", "instruction");
			assertThat(concept.toString()).doesNotContainPattern("[0-9]");
		}
		assertThat(prompt.user() + prompt.system()).doesNotContain("WELL_KNOWN", "SHAKY", "NO_RECORD",
			"0.9", "21", "0.72", "stability", "retrievability", "similarity", "judgments");
		assertThat(prompt.system()).contains("개념마다 받은 지시에 따라 짧게 또는 자세히", "어떤 개념도 판정으로 빼지 않는다")
			.doesNotContain("모든 개념을 처음 배우는 것처럼");
	}

}
