package com.mogum.curriculum.repository;

import com.mogum.curriculum.domain.Lesson;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface LessonRepository extends Repository<Lesson, Long> {
    <S extends Lesson> S save(S entity);

    Optional<Lesson> findById(Long id);

    Optional<Lesson> findByIdAndCurriculum_UserId(Long id, Long userId);
}
