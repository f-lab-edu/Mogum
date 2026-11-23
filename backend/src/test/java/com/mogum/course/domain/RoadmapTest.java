package com.mogum.course.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoadmapTest {
	@Test
	@DisplayName("로드맵 계획은 입력과 제목과 설명을 담고 검토 중으로 시작한다")
	void plansRoadmapWithInputAndGeneratedContent() {
		// given
		var input = new LearningInput("서버 개발", Level.OTHER, "실습 경험", Purpose.OTHER, "개인 프로젝트", "자바 예제");

		// when
		var roadmap = Roadmap.plan(42L, input, "서버 개발 로드맵", "서버를 만들 수 있어요");

		// then
		assertThat(roadmap.getId()).isNull();
		assertThat(roadmap.getCreatedAt()).isNull();
		assertThat(roadmap.getUpdatedAt()).isNull();

		assertThat(roadmap.getUserId()).isEqualTo(42L);
		assertThat(roadmap.getInput()).isSameAs(input);

		assertThat(input.getTopic()).isEqualTo("서버 개발");
		assertThat(input.getLevel()).isEqualTo(Level.OTHER);
		assertThat(input.getLevelOther()).isEqualTo("실습 경험");
		assertThat(input.getPurpose()).isEqualTo(Purpose.OTHER);
		assertThat(input.getPurposeOther()).isEqualTo("개인 프로젝트");
		assertThat(input.getMemo()).isEqualTo("자바 예제");

		assertThat(roadmap.getTitle()).isEqualTo("서버 개발 로드맵");
		assertThat(roadmap.getDescription()).isEqualTo("서버를 만들 수 있어요");
		assertThat(roadmap.getStatus()).isEqualTo(RoadmapStatus.REVIEWING);
	}

	@Test
	@DisplayName("첫 코스를 받으면 검토 중 로드맵이 진행 중으로 바뀐다")
	void startsRoadmapOnce() {
		// given
		var roadmap = RoadmapFixtures.roadmap(1L);

		// when
		roadmap.start();

		// then
		assertThat(roadmap.getStatus()).isEqualTo(RoadmapStatus.IN_PROGRESS);
		assertThatThrownBy(roadmap::start).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("다시 짠 제목과 설명을 함께 바꾸고 입력과 검토 중 상태를 유지한다")
	void replacesPlanContent() {
		// given
		var roadmap = RoadmapFixtures.roadmap(1L);
		var input = roadmap.getInput();

		// when
		roadmap.replan("새 제목", "새 설명");

		// then
		assertThat(roadmap.getTitle()).isEqualTo("새 제목");
		assertThat(roadmap.getDescription()).isEqualTo("새 설명");
		assertThat(roadmap.getInput()).isSameAs(input);
		assertThat(roadmap.getStatus()).isEqualTo(RoadmapStatus.REVIEWING);
	}

	@Test
	@DisplayName("진행 중 로드맵의 계획 변경은 불변식 위반이다")
	void rejectsReplanAfterStart() {
		// given
		var roadmap = RoadmapFixtures.roadmap(1L);
		roadmap.start();

		// when & then
		assertThatThrownBy(() -> roadmap.replan("새 제목", "새 설명"))
			.isInstanceOf(IllegalStateException.class);
		assertThat(roadmap.getTitle()).isEqualTo("자바 로드맵");
		assertThat(roadmap.getDescription()).isEqualTo("자바로 프로그램을 만들 수 있어요");
	}

	@Test
	@DisplayName("진행 중 로드맵만 다 마침이 되고 다시 완료해도 그대로다")
	void completesOnlyInProgressRoadmap() {
		// given
		var roadmap = RoadmapFixtures.roadmap(1L);
		assertThatThrownBy(roadmap::complete).isInstanceOf(IllegalStateException.class);
		roadmap.start();
		roadmap.complete();

		// when
		roadmap.complete();
		// then
		assertThat(roadmap.getStatus()).isEqualTo(RoadmapStatus.COMPLETED);
	}
}
