package com.mogum.job.domain;

public enum JobStatus {
    /** 기다림 — 받았을 때 · 실패를 다시 시도할 때 · 정상적으로 끄는 서버가 돌려놓을 때 */
    QUEUED,

    /** 만드는 중 — 워커가 집었을 때, lease_until 이 찬다 */
    RUNNING,

    /** 성공 — 결과가 생겼을 때, 결과를 가리킨다 */
    SUCCEEDED,

    /** 실패 — 자동 다시 시도로도 안 됐을 때, error_type 이 찬다 */
    FAILED
}
