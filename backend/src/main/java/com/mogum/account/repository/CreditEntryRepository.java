package com.mogum.account.repository;

import com.mogum.account.domain.CreditEntry;
import org.springframework.data.repository.Repository;

public interface CreditEntryRepository extends Repository<CreditEntry, Long> {
    <S extends CreditEntry> S save(S entity);
}
