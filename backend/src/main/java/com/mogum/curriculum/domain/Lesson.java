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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "lessons", uniqueConstraints = @UniqueConstraint(
        name = "lessons_curriculum_order_key", columnNames = {"curriculum_id", "order_no"}))
public class Lesson extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "curriculum_id", nullable = false)
    private Curriculum curriculum;

    @Column(name = "order_no", nullable = false)
    private int orderNo;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String objective;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> keywords;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private LessonStatus status;

    public static Lesson of(Curriculum curriculum, int orderNo, String title, String objective, List<String> keywords) {
        var lesson = new Lesson();
        lesson.curriculum = curriculum;
        lesson.orderNo = orderNo;
        lesson.title = title;
        lesson.objective = objective;
        lesson.keywords = keywords;
        lesson.status = LessonStatus.NOT_STARTED;
        return lesson;
    }
}
