package com.mogum.generation.call.repository;

import com.mogum.generation.call.domain.LlmCall;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface LlmCallRepository extends Repository<LlmCall, Long> {
    <S extends LlmCall> S save(S entity);

    Optional<LlmCall> findById(Long id);
}
