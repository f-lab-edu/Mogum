package com.mogum.curriculum.domain;

import com.mogum.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "curriculum_groups", indexes = @Index(name = "curriculum_groups_user_id_idx", columnList = "user_id"))
public class CurriculumGroup extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "text")
    private String topic;

    @Column(columnDefinition = "text")
    private String purpose;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    public static CurriculumGroup plan(Long userId, String topic, String purpose, String title) {
        var group = new CurriculumGroup();
        group.userId = userId;
        group.topic = topic;
        group.purpose = purpose;
        group.title = title;
        return group;
    }
}
