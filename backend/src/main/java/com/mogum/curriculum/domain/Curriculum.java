package com.mogum.curriculum.domain;

import com.mogum.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "curriculums", uniqueConstraints = @UniqueConstraint(name = "curriculums_step_id_key", columnNames = "step_id"),
        indexes = @Index(name = "curriculums_user_id_idx", columnList = "user_id"))
public class Curriculum extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "text")
    private String topic;

    @Column(columnDefinition = "text")
    private String purpose;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", unique = true)
    private CurriculumStep step;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private CurriculumStatus status;

    public static Curriculum single(Long userId, String topic, String purpose, String title) {
        var curriculum = new Curriculum();
        curriculum.userId = userId;
        curriculum.topic = topic;
        curriculum.purpose = purpose;
        curriculum.title = title;
        curriculum.status = CurriculumStatus.DRAFT;
        return curriculum;
    }

    public static Curriculum forStep(CurriculumStep step) {
        var curriculum = single(step.getGroup().getUserId(), step.getTopic(), step.getPurpose(), step.getTitle());
        curriculum.step = step;
        return curriculum;
    }
}
