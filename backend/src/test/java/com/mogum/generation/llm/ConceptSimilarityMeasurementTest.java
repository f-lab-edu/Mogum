package com.mogum.generation.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.mogum.generation.EmbeddingProvider;
import com.mogum.generation.EmbeddingSettings;
import com.mogum.generation.call.domain.LlmCallOutcome;
import com.mogum.job.domain.JobType;

/** 사람이 llmTest로만 실행한다. 기준값은 보고서를 보고 정하며 DB는 쓰지 않는다. */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".*\\S.*")
class ConceptSimilarityMeasurementTest {
	private static final List<Pair> PAIRS = List.of(
		new Pair(true, "격리 수준",
			"동시에 실행되는 트랜잭션 사이에서 변경 내용을 얼마나 볼 수 있는지 정하는 기준이다.",
			"트랜잭션 격리 수준", "여러 트랜잭션이 함께 실행될 때 서로의 데이터 변경을 관찰할 수 있는 범위를 정한다."),
		new Pair(true, "DB 색인",
			"테이블 행을 모두 읽지 않고 원하는 데이터를 빠르게 찾도록 별도 구조에 키를 저장한다.",
			"데이터베이스 인덱스", "검색할 칼럼의 값을 보조 자료구조로 관리하여 데이터 행을 빨리 찾는 장치이다."),
		new Pair(true, "상호 배제",
			"여러 실행 흐름이 공유 자원에 동시에 접근하지 못하도록 한 번에 하나만 허용한다.",
			"뮤추얼 익스클루전", "공유 자원을 사용하는 임계 구역에 오직 하나의 스레드만 들어가게 한다."),
		new Pair(true, "원자성",
			"트랜잭션의 연산은 전부 반영되거나 전부 취소되어 부분적으로 남지 않는다.",
			"트랜잭션 원자성", "하나의 트랜잭션에 속한 변경을 모두 적용하거나 하나도 적용하지 않는 성질이다."),
		new Pair(true, "멱등성",
			"같은 요청을 여러 번 적용해도 한 번 적용한 것과 최종 상태가 같다.",
			"아이템포턴시", "연산을 반복 실행해도 처음 한 번 실행한 결과와 상태가 달라지지 않는 성질이다."),
		new Pair(true, "교착 상태",
			"여러 스레드가 서로 가진 자원을 기다려 어느 쪽도 진행하지 못하는 상태이다.",
			"데드락", "실행 흐름들이 상대가 점유한 자원의 해제를 서로 기다려 모두 멈추는 현상이다."),
		new Pair(true, "의존성 주입",
			"객체가 사용할 의존 객체를 직접 만들지 않고 외부에서 전달받는다.",
			"DI", "객체가 필요로 하는 협력 객체를 바깥의 조립 코드가 만들어 넣는 방식이다."),
		new Pair(true, "쓰레기 수집",
			"더 이상 접근할 수 없는 객체가 점유한 메모리를 자동으로 회수한다.",
			"가비지 컬렉션", "프로그램에서 도달할 수 없게 된 객체의 메모리 공간을 런타임이 되찾는다."),
		new Pair(true, "후입선출",
			"마지막에 넣은 항목을 가장 먼저 꺼내는 자료 처리 순서이다.",
			"LIFO", "자료구조에 가장 최근 추가된 원소부터 제거하는 규칙이다."),
		new Pair(true, "선입선출",
			"먼저 들어온 항목부터 먼저 꺼내는 자료 처리 순서이다.",
			"FIFO", "자료가 삽입된 순서를 유지하여 가장 오래된 원소부터 제거하는 규칙이다."),
		new Pair(true, "지연 로딩",
			"연관된 데이터를 실제 사용하는 시점까지 읽기를 미루는 방식이다.",
			"레이지 로딩", "객체를 처음 읽을 때 관련 자료를 모두 가져오지 않고 접근할 때 조회한다."),
		new Pair(true, "캐시 무효화",
			"원본이 바뀌거나 유효 기간이 지나 낡은 캐시 항목을 제거하거나 사용하지 않게 한다.",
			"캐시 폐기", "오래된 저장 사본이 재사용되지 않도록 캐시에서 해당 값을 버리는 처리이다."),
		new Pair(true, "이진 탐색",
			"정렬된 자료의 중앙값과 비교하며 탐색 범위를 절반씩 줄여 원하는 값을 찾는다.",
			"바이너리 서치", "정렬된 배열에서 중간 원소를 기준으로 한쪽 절반을 반복 제외하여 검색한다."),
		new Pair(true, "낙관적 잠금",
			"동시 충돌이 드물다고 보고 갱신할 때 버전 등을 비교하여 충돌을 검출한다.",
			"낙관적 락", "먼저 배타 잠금을 잡지 않고 저장 시점의 버전 검사로 동시 수정을 감지한다."),
		new Pair(true, "영속성",
			"커밋된 트랜잭션의 결과가 시스템 장애 뒤에도 보존되는 성질이다.",
			"트랜잭션 지속성", "성공적으로 확정한 데이터 변경은 장애가 발생하더라도 사라지지 않아야 한다."),
		new Pair(false, "인덱스",
			"데이터베이스에서 행을 빠르게 찾기 위해 검색 키를 별도 구조로 관리한다.",
			"인덱스", "배열에서 원소가 놓인 위치를 나타내는 정수 번호이다."),
		new Pair(false, "스택",
			"함수 호출의 지역 변수와 반환 주소 등을 쌓아 실행을 관리하는 메모리 영역이다.",
			"스택", "서비스를 구성하는 언어와 프레임워크 및 데이터베이스 등 기술의 조합이다."),
		new Pair(false, "락",
			"공유 자원에 대한 동시 접근을 제한하는 동기화 장치이다.",
			"락 프리", "개별 스레드가 멈추어도 시스템의 적어도 하나의 실행 흐름은 진행을 보장하는 성질이다."),
		new Pair(false, "낙관적 락",
			"저장 시점에 버전을 검사하여 동시 변경 충돌을 발견한다.",
			"비관적 락", "데이터를 읽을 때 미리 잠금을 잡아 다른 트랜잭션의 충돌하는 접근을 막는다."),
		new Pair(false, "TCP",
			"연결을 맺고 재전송과 순서 제어로 신뢰성 있는 바이트 스트림을 전송하는 프로토콜이다.",
			"UDP", "연결 수립 없이 개별 데이터그램을 보내며 전달과 순서를 보장하지 않는 프로토콜이다."),
		new Pair(false, "인증",
			"제출한 자격 증명을 검증하여 사용자가 누구인지 확인한다.",
			"인가", "확인된 사용자가 특정 자원이나 동작에 접근할 권한이 있는지 결정한다."),
		new Pair(false, "프로세스",
			"독립된 주소 공간과 운영체제 자원을 할당받아 실행되는 프로그램 인스턴스이다.",
			"스레드", "프로세스의 주소 공간을 공유하며 실행 순서를 가지는 작업 단위이다."),
		new Pair(false, "동시성",
			"여러 작업의 실행 구간을 겹쳐 함께 진행하는 구조이다.",
			"병렬성", "여러 연산을 여러 처리 장치에서 같은 순간에 실제로 실행하는 것이다."),
		new Pair(false, "해시",
			"가변 길이 입력을 고정 길이 값으로 바꾸는 일반적으로 역변환할 수 없는 연산이다.",
			"암호화", "키를 사용하여 원문을 숨기고 올바른 키로 원문을 복원할 수 있게 하는 변환이다."),
		new Pair(false, "유니크 키",
			"테이블에서 특정 칼럼 조합의 중복 값을 제한하는 제약이다.",
			"외래 키", "다른 행의 키를 참조하여 테이블 사이 참조 무결성을 유지하는 제약이다."),
		new Pair(false, "스케일 업",
			"기존 서버의 CPU나 메모리 등 자원 용량을 늘려 처리 능력을 높인다.",
			"스케일 아웃", "처리를 나눌 서버의 대수를 늘려 전체 처리 능력을 높인다."),
		new Pair(false, "캐시",
			"자주 사용하는 데이터 사본을 빠른 저장소에 두어 반복 접근 비용을 줄인다.",
			"메시지 큐", "생산자가 보낸 메시지를 보관하고 소비자에게 전달하여 처리를 분리한다."),
		new Pair(false, "정규화",
			"관계형 테이블의 중복과 갱신 이상을 줄이도록 종속성에 따라 테이블을 분해한다.",
			"역정규화", "조회 성능 등을 위해 분리된 데이터를 합치거나 중복 저장하는 설계 방법이다."),
		new Pair(false, "이진 탐색",
			"정렬된 원소를 중앙값과 비교하며 검색 범위를 절반씩 줄여 값을 찾는다.",
			"가비지 컬렉션", "더 이상 참조되지 않는 객체의 메모리를 자동으로 회수한다."),
		new Pair(false, "의존성 주입",
			"객체가 필요로 하는 협력 객체를 외부에서 전달받아 조립한다.",
			"TCP 혼잡 제어", "네트워크의 혼잡 정도에 맞추어 송신 측의 전송량을 조절한다."));

	@Test
	@DisplayName("한국어 개념 30쌍의 정의를 한 번에 임베딩하여 기준값 검토 보고서를 쓴다")
	void measuresConceptSimilarity() throws Exception {
		// given
		assertThat(PAIRS.stream().filter(Pair::same).count()).isGreaterThanOrEqualTo(15);
		assertThat(PAIRS.stream().filter(pair -> !pair.same()).count()).isGreaterThanOrEqualTo(15);
		var settings = new EmbeddingSettings("text-embedding-3-large", Duration.ofSeconds(20));
		var definitions = PAIRS.stream()
			.flatMap(pair -> java.util.stream.Stream.of(pair.firstDefinition(), pair.secondDefinition())).toList();
		try (var client = new OpenAiChatProvider(System.getenv("OPENAI_API_KEY"), null, settings.timeout())) {
			var provider = new OpenAiEmbeddingProvider(client);

			// when
			var reply = provider.send(new EmbeddingProvider.Request(0, JobType.MATERIAL, settings, definitions));

			// then
			assertThat(reply.outcome()).isEqualTo(LlmCallOutcome.SUCCEEDED);
			assertThat(reply.vectors()).hasSize(definitions.size());
			List<Measurement> measurements = new ArrayList<>();
			for (int index = 0; index < PAIRS.size(); index++) {
				measurements.add(new Measurement(PAIRS.get(index),
					cosine(reply.vectors().get(index * 2), reply.vectors().get(index * 2 + 1))));
			}
			var report = Path.of("build/reports/concept-similarity.txt");
			Files.createDirectories(report.getParent());
			Files.writeString(report, report(measurements), StandardCharsets.UTF_8);
		}
	}

	private static double cosine(List<Float> first, List<Float> second) {
		assertThat(first).isNotEmpty().hasSameSizeAs(second);
		double product = 0;
		double firstNorm = 0;
		double secondNorm = 0;
		for (int index = 0; index < first.size(); index++) {
			product += (double)first.get(index) * second.get(index);
			firstNorm += (double)first.get(index) * first.get(index);
			secondNorm += (double)second.get(index) * second.get(index);
		}
		assertThat(firstNorm).isPositive();
		assertThat(secondNorm).isPositive();
		return product / Math.sqrt(firstNorm * secondNorm);
	}

	private static String report(List<Measurement> measurements) {
		var text = new StringBuilder("한국어 개발 개념 정의의 코사인 유사도 — 기준값은 사람이 정한다\n");
		measurements.forEach(measurement -> text.append(line(measurement)));
		double sameMin = measurements.stream().filter(measurement -> measurement.pair().same())
			.mapToDouble(Measurement::similarity).min().orElseThrow();
		double differentMax = measurements.stream().filter(measurement -> !measurement.pair().same())
			.mapToDouble(Measurement::similarity).max().orElseThrow();
		text.append(String.format(Locale.ROOT, "\n같은 쌍 최솟값: %.6f\n다른 쌍 최댓값: %.6f\n",
			sameMin, differentMax));
		text.append("둘 사이에 걸치는 쌍 (같은 쌍 최솟값 이상 · 다른 쌍 최댓값 이하):\n");
		var overlap = measurements.stream().filter(measurement -> measurement.similarity() >= sameMin
			&& measurement.similarity() <= differentMax).toList();
		if (overlap.isEmpty()) {
			text.append("없음\n");
		} else {
			overlap.forEach(measurement -> text.append(line(measurement)));
		}
		return text.toString();
	}

	private static String line(Measurement measurement) {
		return String.format(Locale.ROOT, "%s · %s / %s · %.6f\n", measurement.pair().same() ? "같음" : "다름",
			measurement.pair().firstName(), measurement.pair().secondName(), measurement.similarity());
	}

	private record Pair(boolean same, String firstName, String firstDefinition,
		String secondName, String secondDefinition) {
	}

	private record Measurement(Pair pair, double similarity) {
	}
}
