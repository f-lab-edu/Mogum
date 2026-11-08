package com.mogum.account.repository;

import com.mogum.account.domain.User;
import com.mogum.account.domain.UserFixtures;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class FakeUserRepository implements UserRepository {
    private final Map<Long, User> users = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public void lockIdentity(String provider, String subject) { }

    @Override
    public <S extends User> S save(S entity) {
        if (entity.getId() == null) {
            while (users.containsKey(nextId)) nextId++;
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
    public Optional<User> findByProviderAndSubject(String provider, String subject) {
        return users.values().stream()
                .filter(user -> user.getProvider().equals(provider) && user.getSubject().equals(subject))
                .findFirst();
    }

    public long count() {
        return users.size();
    }
}
