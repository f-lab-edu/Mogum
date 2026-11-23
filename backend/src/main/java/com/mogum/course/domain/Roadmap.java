package com.mogum.course.domain;

import com.mogum.common.persistence.AuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "roadmaps")
public class Roadmap extends AuditedEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false, columnDefinition = "text")
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "text")
	private RoadmapStatus status;

	@Embedded
	private LearningInput input;

	public static Roadmap plan(long userId, LearningInput input, String title, String description) {
		var roadmap = new Roadmap();
		roadmap.userId = userId;
		roadmap.input = input;
		roadmap.title = title;
		roadmap.description = description;
		roadmap.status = RoadmapStatus.REVIEWING;
		return roadmap;
	}

	public void start() {
		if (status != RoadmapStatus.REVIEWING) {
			throw new IllegalStateException("Roadmap must be reviewing");
		}
		status = RoadmapStatus.IN_PROGRESS;
	}

	public void replan(String title, String description) {
		if (status != RoadmapStatus.REVIEWING) {
			throw new IllegalStateException("Roadmap must be reviewing");
		}
		this.title = title;
		this.description = description;
	}

	public void complete() {
		if (status == RoadmapStatus.COMPLETED) {
			return;
		}
		if (status != RoadmapStatus.IN_PROGRESS) {
			throw new IllegalStateException("Roadmap must be in progress");
		}
		status = RoadmapStatus.COMPLETED;
	}
}
