package com.mogum.account.repository;

import com.mogum.account.domain.CreditEntry;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FakeCreditEntryRepository implements CreditEntryRepository {
    private final Map<Long, CreditEntry> entries = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public <S extends CreditEntry> S save(S entry) {
        if (entry.getId() == null) ReflectionTestUtils.setField(entry, "id", nextId++);
        entries.put(entry.getId(), entry);
        return entry;
    }

    public List<CreditEntry> entries() { return List.copyOf(entries.values()); }
}
