package com.mogum.job.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mogum.common.api.ApiError;
import com.mogum.job.domain.JobStatus;
import com.mogum.job.domain.JobType;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobResponse(long id, JobType type, JobStatus status, Subject subject, Result result, ApiError error) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subject(String topic, String level, String levelOther, String purpose, String purposeOther, String title) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String type, long id, Long courseId) { }
}
