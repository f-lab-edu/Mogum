package com.mogum.account.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;

public final class FakeUserRepository implements UserRepository {
	private final Map<Long, User> users = new LinkedHashMap<>();
	private long nextId = 1;

	@Override
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	public void lockIdentity(String provider, String subject) {}
	// @checkstyle:on

	@Override
	public <S extends User> S save(S entity) {
		if (entity.getId() == null) {
			while (users.containsKey(nextId)) {
				nextId++;
			}
			UserFixtures.withId(entity, nextId++);
		}

		users.put(entity.getId(), entity);
		return entity;
	}

	@Override
	public Optional<User> findById(Long id) {
		return Optional.ofNullable(users.get(id));
	}

	@Override
	public Optional<User> findByIdForUpdate(long id) {
		return findById(id);
	}

	@Override
	public Optional<User> findByProviderAndSubject(String provider, String subject) {
		return users.values()
			.stream()
			.filter(user -> user.getProvider().equals(provider) && user.getSubject().equals(subject))
			.findFirst();
	}

	@Override
	public List<Long> findApprovedIdsDueForRefill(Instant now) {
		return users.values().stream()
			.filter(user -> user.getStatus() == UserStatus.APPROVED && !user.getCreditRefillAt().isAfter(now))
			.map(User::getId).sorted().toList();
	}

	public long count() {
		return users.size();
	}
}
