package com.mogum.generation;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;

import com.mogum.card.domain.Judgment;
import com.mogum.card.service.ConceptLookupService;
import com.mogum.card.service.LessonCardWriter;
import com.mogum.card.service.LessonCardWriter.NewCard;
import com.mogum.card.service.LessonCardWriter.NewConcept;
import com.mogum.course.domain.Material;
import com.mogum.course.repository.LessonRepository;
import com.mogum.course.repository.MaterialRepository;
import com.mogum.generation.material.MaterialContent;
import com.mogum.generation.material.MaterialFormat;
import com.mogum.job.domain.JobType;

import lombok.RequiredArgsConstructor;

/** 한 시도의 채팅과 일괄 임베딩 뒤, 소유권을 확인한 워커 트랜잭션에서 결과를 함께 쓴다. */
@RequiredArgsConstructor
public final class MaterialGenerator implements JobGenerator {
	private final ChatCall chatCall;
	private final ChatSettings chatSettings;
	private final EmbeddingCall embeddingCall;
	private final EmbeddingSettings embeddingSettings;
	private final MaterialFormat materialFormat;
	private final MaterialReader materialReader;
	private final LessonRepository lessonRepository;
	private final MaterialRepository materialRepository;
	private final LessonCardWriter lessonCardWriter;
	private final ConceptLookupService conceptLookupService;
	private final Clock clock;

	@Override
	public JobType type() {
		return JobType.MATERIAL;
	}

	@Override
	public GenerationOutcome generate(JobSnapshot job) {
		try {
			var input = materialReader.read(job.targetId());
			if (conceptLookupService.hasConcepts(job.userId())) {
				var definitions = input.lesson().concepts().stream().map(concept -> concept.definition()).distinct()
					.toList();
				if (!definitions.isEmpty()) {
					var embedding = embeddingCall.call(job, embeddingSettings, definitions);
					if (embedding instanceof EmbeddingResult.Failed failed) {
						return failed.failure();
					}
					var vectors = ((EmbeddingResult.Success)embedding).value().stream()
						.map(MaterialGenerator::vector).toList();
					var judgments = conceptLookupService.judgeMatching(job.userId(), vectors, clock.instant());
					var byDefinition = new LinkedHashMap<String, Judgment>();
					for (int index = 0; index < definitions.size(); index++) {
						byDefinition.put(definitions.get(index), judgments.get(index));
					}
					input = input.withJudgments(byDefinition);
				}
			}
			var prompt = materialFormat.prompt(input);
			var chat = chatCall.call(job, chatSettings, prompt.system(), prompt.user(), materialFormat::parse);
			if (chat instanceof ChatResult.Failed<MaterialContent> failed) {
				return failed.failure();
			}
			var content = ((ChatResult.Success<MaterialContent>)chat).value();
			var definitions = content.cards().stream().flatMap(card -> card.concepts().stream())
				.map(concept -> concept.definition()).distinct().toList();
			var embedding = embeddingCall.call(job, embeddingSettings, definitions);
			if (embedding instanceof EmbeddingResult.Failed failed) {
				return failed.failure();
			}
			var vectors = ((EmbeddingResult.Success)embedding).value();
			var byDefinition = new LinkedHashMap<String, float[]>();
			for (int index = 0; index < definitions.size(); index++) {
				byDefinition.put(definitions.get(index), vector(vectors.get(index)));
			}
			var cards = content.cards().stream().map(card -> new NewCard(card.question(), card.answer(),
				card.concepts().stream().map(concept -> new NewConcept(concept.name(), concept.definition(),
					byDefinition.get(concept.definition()))).toList()))
				.toList();
			return new GenerationOutcome.Success(new Result(content.body(), cards));
		} catch (RuntimeException exception) {
			return GenerationFailures.unavailable(exception);
		}
	}

	private static float[] vector(List<Float> values) {
		var vector = new float[values.size()];
		for (int dimension = 0; dimension < vector.length; dimension++) {
			vector[dimension] = values.get(dimension);
		}
		return vector;
	}

	@Override
	public ResultRef save(JobSnapshot job, GeneratedResult result) {
		var lesson = lessonRepository.findById(job.targetId()).orElseThrow();
		var material = (Result)result;
		materialRepository.save(Material.write(lesson, material.body()));
		lessonCardWriter.writeLessonCards(job.userId(), lesson.getId(), material.cards());
		return new ResultRef(lesson.getId());
	}

	public record Result(String body, List<NewCard> cards) implements GeneratedResult {
		public Result {
			cards = List.copyOf(cards);
		}
	}
}
