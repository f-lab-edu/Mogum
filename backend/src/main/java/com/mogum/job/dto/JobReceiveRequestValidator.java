package com.mogum.job.dto;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.mogum.common.api.FieldViolation.Reason;
import com.mogum.course.domain.Level;
import com.mogum.course.domain.Purpose;
import com.mogum.job.domain.JobType;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** V11의 종류별 입력 · 대상 · 기타 글 CHECK를 HTTP 경계에서 검사한다. */
public final class JobReceiveRequestValidator implements ConstraintValidator<ValidJobRequest, JobReceiveRequest> {
	private static final Pattern BLANK = Pattern.compile("\\s*", Pattern.UNICODE_CHARACTER_CLASS);

	@Override
	public boolean isValid(JobReceiveRequest request, ConstraintValidatorContext context) {
		if (request == null)
			return true;

		var errors = new LinkedHashMap<String, Reason>();
		choice(errors, "type", request.type(), JobType.values());
		var type = Arrays.stream(JobType.values())
			.filter(value -> value.name().equals(request.type()))
			.findFirst();

		if (type.isPresent()) {
			boolean fresh = type.get() == JobType.NEW_COURSE || type.get() == JobType.NEW_ROADMAP;
			if (fresh) {
				text(errors, "topic", request.topic(), 100, true);
				choice(errors, "level", request.level(), Level.values());
				choice(errors, "purpose", request.purpose(), Purpose.values());
				other(errors, "levelOther", request.levelOther(), "OTHER".equals(request.level()));
				other(errors, "purposeOther", request.purposeOther(), "OTHER".equals(request.purpose()));
				text(errors, "memo", request.memo(), 500, false);
			} else {
				absent(errors, "topic", request.topic());
				absent(errors, "level", request.level());
				absent(errors, "levelOther", request.levelOther());
				absent(errors, "purpose", request.purpose());
				absent(errors, "purposeOther", request.purposeOther());
				absent(errors, "memo", request.memo());
			}

			target(errors, "courseId", request.courseId(),
				type.get() == JobType.PLAN_ROADMAP_COURSE || type.get() == JobType.REPLAN_COURSE);
			target(errors, "roadmapId", request.roadmapId(), type.get() == JobType.REPLAN_ROADMAP);
			target(errors, "lessonId", request.lessonId(), type.get() == JobType.MATERIAL);
			if (type.get() == JobType.REPLAN_COURSE || type.get() == JobType.REPLAN_ROADMAP) {
				text(errors, "feedback", request.feedback(), 500, false);
			} else {
				absent(errors, "feedback", request.feedback());
			}
		}

		if (errors.isEmpty())
			return true;

		context.disableDefaultConstraintViolation();
		errors.forEach((field, reason) -> context.buildConstraintViolationWithTemplate("field." + reason.name())
			.addPropertyNode(field)
			.addConstraintViolation());
		return false;
	}

	private static void target(Map<String, Reason> errors, String field, Long value, boolean required) {
		if (required && value == null)
			errors.put(field, Reason.REQUIRED);
		if (!required)
			absent(errors, field, value);
	}

	private static void other(Map<String, Reason> errors, String field, String value, boolean required) {
		if (required)
			text(errors, field, value, 50, true);
		else
			absent(errors, field, value);
	}

	private static void absent(Map<String, Reason> errors, String field, Object value) {
		if (value != null)
			errors.put(field, Reason.NOT_ALLOWED);
	}

	private static void text(Map<String, Reason> errors, String field, String value, int max, boolean required) {
		if (value == null) {
			if (required)
				errors.put(field, Reason.REQUIRED);
		} else if (BLANK.matcher(value).matches())
			errors.put(field, Reason.REQUIRED);
		else if (value.codePointCount(0, value.length()) > max)
			errors.put(field, Reason.TOO_LONG);
	}

	private static void choice(Map<String, Reason> errors, String field, String value, Enum<?>[] choices) {
		if (value == null || BLANK.matcher(value).matches()) {
			errors.put(field, Reason.REQUIRED);
		} else if (Arrays.stream(choices).noneMatch(choice -> choice.name().equals(value))) {
			errors.put(field, Reason.INVALID_VALUE);
		}
	}
}
