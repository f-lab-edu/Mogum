package com.mogum.account.repository;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.CreditEntryType;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface CreditEntryRepository extends Repository<CreditEntry, Long> {
    <S extends CreditEntry> S save(S entity);

    Optional<CreditEntry> findFirstByUserIdAndJobIdOrderByIdDesc(long userId, long jobId);

    Optional<CreditEntry> findFirstByUserIdAndTypeOrderByIdDesc(long userId, CreditEntryType type);
}
