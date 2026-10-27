package com.mogum.curriculum.domain;

import com.mogum.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "curriculum_steps", uniqueConstraints = @UniqueConstraint(
        name = "curriculum_steps_group_order_key", columnNames = {"group_id", "order_no"}))
public class CurriculumStep extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private CurriculumGroup group;

    @Column(name = "order_no", nullable = false)
    private int orderNo;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String scope;

    @Column(nullable = false, columnDefinition = "text")
    private String topic;

    @Column(columnDefinition = "text")
    private String purpose;

    public static CurriculumStep of(CurriculumGroup group, int orderNo, String title, String scope, String topic, String purpose) {
        var step = new CurriculumStep();
        step.group = group;
        step.orderNo = orderNo;
        step.title = title;
        step.scope = scope;
        step.topic = topic;
        step.purpose = purpose;
        return step;
    }
}
