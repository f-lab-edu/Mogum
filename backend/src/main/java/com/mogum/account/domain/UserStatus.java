package com.mogum.account.domain;

/** 계정 상태 — 신청 전(NOT_APPLIED)은 DB 상태가 아니라 여기 없다. */
public enum UserStatus {
    /** 신청 — 신청하고 승인을 기다린다 — 거절을 풀어도 여기로 돌아온다 */
    APPLIED,
    /** 승인 — 운영자가 승인했다 — 머금을 쓴다 */
    APPROVED,
    /** 거절됨 — 운영자가 거절했다 — 스스로 다시 신청하지 못한다 */
    REJECTED
}
