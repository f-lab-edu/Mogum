package com.mogum.course.repository;

import com.mogum.course.domain.Course;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface CourseRepository extends Repository<Course, Long> {
    <S extends Course> S save(S entity);

    Optional<Course> findById(Long id);
}

