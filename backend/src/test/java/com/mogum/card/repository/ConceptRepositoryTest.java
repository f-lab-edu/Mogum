package com.mogum.card.repository;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.card.domain.ConceptFixtures;
import com.mogum.support.RepositoryTest;
import com.mogum.support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class ConceptRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private ConceptRepository concepts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestClock clock;

    @Test
    @DisplayName("차원 없는 vector 매핑은 3차원과 5차원 임베딩의 값과 개념 및 시각을 함께 보존한다")
    void reloadsEmbeddingsWithDifferentDimensions() {
        // given
        var user = users.save(UserFixtures.user("me@example.com"));
        var shortVector = new float[]{0.25f, -0.5f, 1.125f};
        var longVector = new float[]{-1, 0, 0.125f, 2.5f, 3};
        var first = concepts.save(ConceptFixtures.concept(user.getId(), "변수", "값을 담는 이름", shortVector));
        var second = concepts.save(ConceptFixtures.concept(user.getId(), "타입", "값의 종류", longVector));

        // when
        var actual = concepts.findById(first.getId()).orElseThrow();

        // then
        assertThat(actual).isNotSameAs(first);
        assertThat(actual.getId()).isPositive();
        assertThat(actual.getUserId()).isEqualTo(user.getId());
        assertThat(actual.getName()).isEqualTo("변수");
        assertThat(actual.getDefinition()).isEqualTo("값을 담는 이름");
        assertThat(actual.getEmbedding()).containsExactly(shortVector);
        assertThat(actual.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(actual.getUpdatedAt()).isEqualTo(clock.instant());
        var other = concepts.findById(second.getId()).orElseThrow();
        assertThat(other).isNotSameAs(second);
        assertThat(other.getUserId()).isEqualTo(user.getId());
        assertThat(other.getName()).isEqualTo("타입");
        assertThat(other.getDefinition()).isEqualTo("값의 종류");
        assertThat(other.getEmbedding()).containsExactly(longVector);
        assertThat(jdbc.queryForList("SELECT vector_dims(embedding) FROM concepts ORDER BY id", Integer.class))
                .containsExactly(3, 5);
        assertThat(jdbc.queryForObject("""
                SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                WHERE attrelid = 'concepts'::regclass AND attname = 'embedding'
                """, String.class)).isEqualTo("vector");
    }
}
