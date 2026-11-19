package com.mogum.job.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mogum.course.domain.LearningInput;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.job.JobRequest;
import com.mogum.job.domain.JobType;

@JsonIgnoreProperties(ignoreUnknown = true)
@ValidJobRequest
public record JobReceiveRequest(String type, Long courseId, Long roadmapId, Long lessonId,
        String topic, String level, String levelOther, String purpose,
        String purposeOther, String memo, String feedback) {
    /** @Valid를 통과한 값만 step 2의 받기 계약으로 바꾼다. */
    public JobRequest toJobRequest() {
        var jobType = JobType.valueOf(type);
        Long target = switch (jobType) {
            case NEW_COURSE, NEW_ROADMAP -> null;
            case PLAN_ROADMAP_COURSE, REPLAN_COURSE -> courseId;
            case REPLAN_ROADMAP -> roadmapId;
            case MATERIAL -> lessonId;
        };
        var input = jobType == JobType.NEW_COURSE || jobType == JobType.NEW_ROADMAP
                ? new LearningInput(topic, Level.valueOf(level), levelOther,
                        Purpose.valueOf(purpose), purposeOther, memo)
                : null;
        return new JobRequest(jobType, target, input, feedback);
    }
}
