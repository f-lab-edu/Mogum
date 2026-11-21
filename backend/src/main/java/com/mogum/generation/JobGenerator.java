package com.mogum.generation;

import com.mogum.job.domain.JobType;

/** 종류마다 다른 만들기와 결과 저장의 자리. 운영 구현은 해당 생성 TASK에서 더한다. */
public interface JobGenerator {
	JobType type();

	/** 트랜잭션 밖에서 값만 받아 만든다. 유료 호출과 이어 쓰기는 이 안의 일이다. */
	GenerationOutcome generate(JobSnapshot job);

	/** 소유권 확인을 첫 쓰기로 마친 트랜잭션 안에서 결과와 입력·상태를 함께 저장한다. */
	ResultRef save(JobSnapshot job, GeneratedResult result);
}
