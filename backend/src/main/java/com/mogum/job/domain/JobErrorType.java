package com.mogum.job.domain;

public enum JobErrorType {
    /** 연결 오류 · 시간 초과 · 몰림이 자동 다시 시도 뒤에도 이어졌다 */
    PROVIDER_UNAVAILABLE,
    /** 형식 검사 불합격이 이어졌다 · 이어 써도 끝나지 않았다 */
    INVALID_OUTPUT,
    /** 키 · 쿼터 같은 설정 문제 */
    PROVIDER_REJECTED,
    /** 입력이 너무 크다 */
    INPUT_TOO_LARGE
}
