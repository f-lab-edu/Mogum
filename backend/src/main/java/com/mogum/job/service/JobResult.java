package com.mogum.job.service;

import com.mogum.job.domain.JobStatus;

public record JobResult(long id, JobStatus status) {
}
