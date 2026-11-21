package com.mogum.course.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.mogum.common.persistence.AuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "courses", uniqueConstraints = @UniqueConstraint(name = "courses_roadmap_id_position_key", columnNames = {
	"roadmap_id", "position"}))
public class Course extends AuditedEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "roadmap_id")
	private Roadmap roadmap;

	@Column
	private Integer position;

	@Column(nullable = false, columnDefinition = "text")
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "text")
	private CourseStatus status;

	@Embedded
	private LearningInput input;

	@Column(name = "plan_description", columnDefinition = "text")
	private String planDescription;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "plan_concepts", columnDefinition = "jsonb")
	private List<PlannedConcept> planConcepts;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "last_lesson_opened_at")
	private Instant lastLessonOpenedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	public static Course single(long userId, LearningInput input, String title) {
		var course = new Course();
		course.userId = userId;
		course.input = input;
		course.title = title;
		course.status = CourseStatus.REVIEWING;
		return course;
	}

	public static Course inRoadmap(Roadmap roadmap, int position, String title, String planDescription,
		List<PlannedConcept> planConcepts) {
		var course = new Course();
		course.roadmap = roadmap;
		course.userId = roadmap.getUserId();
		course.position = position;
		course.title = title;
		course.planDescription = planDescription;
		course.planConcepts = List.copyOf(planConcepts);
		course.status = CourseStatus.NOT_PLANNED;
		return course;
	}

	public void start(Instant startedAt) {
		if (status != CourseStatus.REVIEWING) {
			throw new IllegalStateException("Course must be reviewing");
		}
		this.startedAt = Objects.requireNonNull(startedAt);
		status = CourseStatus.LEARNING;
	}

	public void rename(String title) {
		this.title = title;
	}

	public void planLessons() {
		if (status != CourseStatus.NOT_PLANNED) {
			throw new IllegalStateException("Course must be unplanned");
		}
		status = CourseStatus.REVIEWING;
	}
}
