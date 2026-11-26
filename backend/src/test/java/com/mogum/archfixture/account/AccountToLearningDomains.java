package com.mogum.archfixture.account;

import com.mogum.card.domain.Card;
import com.mogum.course.domain.Course;
import com.mogum.job.domain.GenerationJob;

public record AccountToLearningDomains(Course course, Card card, GenerationJob job) {
}
