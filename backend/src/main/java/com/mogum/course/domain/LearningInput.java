package com.mogum.course.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningInput {
    @Column(columnDefinition = "text")
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    private Level level;

    @Column(name = "level_other", columnDefinition = "text")
    private String levelOther;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    private Purpose purpose;

    @Column(name = "purpose_other", columnDefinition = "text")
    private String purposeOther;

    @Column(columnDefinition = "text")
    private String memo;

    public LearningInput(String topic, Level level, String levelOther, Purpose purpose, String purposeOther, String memo) {
        this.topic = topic;
        this.level = level;
        this.levelOther = levelOther;
        this.purpose = purpose;
        this.purposeOther = purposeOther;
        this.memo = memo;
    }
}

