package com.mogum.course.repository;

import com.mogum.course.domain.Lesson;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface LessonRepository extends Repository<Lesson, Long> {
    <S extends Lesson> S save(S entity);

    Optional<Lesson> findById(Long id);
}

