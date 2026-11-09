package com.mogum.course.domain;

import com.mogum.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "lessons", uniqueConstraints = @UniqueConstraint(
        name = "lessons_course_id_position_key", columnNames = {"course_id", "position"}))
public class Lesson extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<PlannedConcept> concepts;

    @Column(name = "completed_at")
    private Instant completedAt;

    public static Lesson listed(Course course, int position, String title, String description,
                                List<PlannedConcept> concepts) {
        var lesson = new Lesson();
        lesson.course = course;
        lesson.position = position;
        lesson.title = title;
        lesson.description = description;
        lesson.concepts = List.copyOf(concepts);
        return lesson;
    }
}

