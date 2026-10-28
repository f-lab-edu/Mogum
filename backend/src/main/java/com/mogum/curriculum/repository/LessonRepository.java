package com.mogum.curriculum.repository;

import com.mogum.curriculum.domain.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LessonRepository extends JpaRepository<Lesson, Long> {
    Optional<Lesson> findByIdAndCurriculum_UserId(Long id, Long userId);
}
