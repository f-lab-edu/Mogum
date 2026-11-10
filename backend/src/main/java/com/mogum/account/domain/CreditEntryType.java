package com.mogum.account.domain;

/** 크레딧이 움직인 까닭. */
public enum CreditEntryType {
    /** 채움 — 신청할 때 · 다음에 채우는 때(credit_refill_at)가 지났을 때 — 한 달 몫으로 다시 맞춘다 */
    REFILL,
    /** 씀 — 만들기를 받을 때 · 다시 시도할 때(−) */
    CONSUME,
    /** 복구 — 성공하지 않은 작업이 실패 · 버리기 · 지우기로 끝날 때(+) — 조건은 02 §8.2 */
    RESTORE
}
