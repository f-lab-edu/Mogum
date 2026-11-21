package com.mogum.generation.call.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.mogum.generation.call.domain.LlmCall;

public interface LlmCallRepository extends Repository<LlmCall, Long> {
	<S extends LlmCall> S save(S entity);

	Optional<LlmCall> findById(Long id);
}
