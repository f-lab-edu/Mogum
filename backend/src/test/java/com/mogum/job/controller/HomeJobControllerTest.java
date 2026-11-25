package com.mogum.job.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.mogum.account.domain.UserStatus;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiError;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;
import com.mogum.job.dto.JobResponse;
import com.mogum.job.service.JobReadService;

@WebMvcTest(HomeJobController.class)
@AutoConfigureMockMvc(addFilters = false)
class HomeJobControllerTest {
	@Autowired
	MockMvcTester mockMvcTester;
	@MockitoBean
	JobReadService jobReadService;

	@BeforeEach
	void authenticate() {
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
			new AccountPrincipal.Member(7L, "me@example.com", "나", UserStatus.APPROVED), null, List.of()));
		SecurityContextHolder.setContext(context);
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("홈 작업은 기존 작업 응답의 요약과 실패 두 묶음만 싣고 시스템 정보 칸은 없다")
	void readsJobsUsingSingleJobResponseShape() {
		// given
		given(jobReadService.unfinished(7L)).willReturn(List.of(
			new JobResponse(14L, JobType.NEW_COURSE, JobStatus.QUEUED,
				new JobResponse.Subject("물리", "OTHER", "수학은 알아요", "OTHER", "연구", null), null, null),
			new JobResponse(13L, JobType.MATERIAL, JobStatus.RUNNING,
				new JobResponse.Subject(null, null, null, null, null, "강의 제목"), null, null),
			new JobResponse(12L, JobType.REPLAN_COURSE, JobStatus.FAILED,
				new JobResponse.Subject(null, null, null, null, null, "코스 제목"), null,
				new ApiError("JOB_TEMPORARY_FAILURE", "만들지 못했습니다. 다시 시도하거나 버려 주세요", null, null)),
			new JobResponse(11L, JobType.NEW_ROADMAP, JobStatus.FAILED,
				new JobResponse.Subject("자바", "BEGINNER", null, "WORK", null, null), null,
				new ApiError("JOB_INPUT_FAILURE", "주제를 좁혀 새로 만들어 주세요", null, null))));

		// when
		var result = mockMvcTester.get().uri("/api/home/jobs").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
			{"data":{"jobs":[
			{"id":14,"type":"NEW_COURSE","status":"QUEUED",
			"subject":{"topic":"물리","level":"OTHER","levelOther":"수학은 알아요",
			"purpose":"OTHER","purposeOther":"연구"}},
			{"id":13,"type":"MATERIAL","status":"RUNNING","subject":{"title":"강의 제목"}},
			{"id":12,"type":"REPLAN_COURSE","status":"FAILED","subject":{"title":"코스 제목"},
			"error":{"code":"JOB_TEMPORARY_FAILURE","message":"만들지 못했습니다. 다시 시도하거나 버려 주세요"}},
			{"id":11,"type":"NEW_ROADMAP","status":"FAILED",
			"subject":{"topic":"자바","level":"BEGINNER","purpose":"WORK"},
			"error":{"code":"JOB_INPUT_FAILURE","message":"주제를 좁혀 새로 만들어 주세요"}}
			]}}
			""");
		then(jobReadService).should().unfinished(7L);
	}

	@Test
	@DisplayName("홈 작업이 없으면 객체 안의 빈 jobs 목록을 반환한다")
	void readsEmptyJobs() {
		// given
		given(jobReadService.unfinished(7L)).willReturn(List.of());

		// when
		var result = mockMvcTester.get().uri("/api/home/jobs").exchange();

		// then
		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("{\"data\":{\"jobs\":[]}}");
	}
}
