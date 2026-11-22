package com.mogum.account.repository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.test.util.ReflectionTestUtils;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.CreditEntryType;

public final class FakeCreditEntryRepository implements CreditEntryRepository {
	private final Map<Long, CreditEntry> entries = new LinkedHashMap<>();
	private long nextId = 1;

	@Override
	public <S extends CreditEntry> S save(S entry) {
		if (entry.getId() == null) {
			ReflectionTestUtils.setField(entry, "id", nextId++);
		}
		entries.put(entry.getId(), entry);
		return entry;
	}

	public List<CreditEntry> entries() {
		return List.copyOf(entries.values());
	}

	@Override
	public Optional<CreditEntry> findFirstByUserIdAndJobIdOrderByIdDesc(long userId, long jobId) {
		return entries.values()
			.stream()
			.filter(entry -> entry.getUser().getId() == userId && Objects.equals(entry.getJobId(), jobId))
			.max(Comparator.comparing(CreditEntry::getId));
	}

	@Override
	public Optional<CreditEntry> findFirstByUserIdAndTypeOrderByIdDesc(long userId, CreditEntryType type) {
		return entries.values()
			.stream()
			.filter(entry -> entry.getUser().getId() == userId && entry.getType() == type)
			.max(Comparator.comparing(CreditEntry::getId));
	}
}
