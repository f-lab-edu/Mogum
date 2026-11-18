package com.mogum.course.service;

import com.mogum.account.service.CreditLedger;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.course.domain.Course;
import com.mogum.course.domain.CourseStatus;
import com.mogum.course.dto.CourseResponse;
import com.mogum.course.repository.CourseJobRepository;
import com.mogum.course.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@Transactional
@RequiredArgsConstructor
public class CourseCommandService {
    private final CourseRepository courseRepository;
    private final CourseJobRepository courseJobRepository;
    private final CreditLedger creditLedger;
    private final CourseReadService courseReadService;
    private final Clock clock;

    public CourseResponse start(long userId, long courseId) {
        creditLedger.lock(userId);
        var course = ownedCourse(userId, courseId);

        if (course.getStatus() == CourseStatus.NOT_PLANNED) {
            throw new ApiException(ErrorCode.COURSE_START_NOT_ALLOWED);
        }
        if (course.getStatus() == CourseStatus.REVIEWING) {
            if (courseJobRepository.hasUnfinishedReplan(userId, courseId)) {
                throw new ApiException(ErrorCode.REPLAN_IN_PROGRESS);
            }
            course.start(clock.instant());
        }

        return courseReadService.read(userId, courseId);
    }

    public CourseResponse rename(long userId, long courseId, String title) {
        var course = ownedCourse(userId, courseId);
        course.rename(title);
        return courseReadService.read(userId, courseId);
    }

    private Course ownedCourse(long userId, long courseId) {
        return courseRepository.findById(courseId)
                .filter(course -> course.getUserId() == userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
