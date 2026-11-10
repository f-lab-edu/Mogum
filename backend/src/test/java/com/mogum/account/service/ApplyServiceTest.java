package com.mogum.account.service;

import com.mogum.account.domain.CreditEntryType;
import com.mogum.account.domain.UserFixtures;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.repository.FakeCreditEntryRepository;
import com.mogum.account.repository.FakeUserRepository;
import com.mogum.auth.AccountPrincipal;
import com.mogum.config.CreditProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ApplyServiceTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("신청 전 신원으로 신청하면 한 달 몫의 잔액과 채움 기록을 함께 만든다")
    void createsAppliedUserAndInitialRefill() {
        runner.withPropertyValues("app.credit.monthly-amount=37").run(context -> {
            // given
            var credit = context.getBean(CreditProperties.class);
            var users = new FakeUserRepository();
            var entries = new FakeCreditEntryRepository();
            var service = new ApplyService(users, entries, credit);
            var identity = new AccountPrincipal.NotApplied("google", "new-sub", "new@example.com", "구글 이름");

            // when
            var result = service.apply(identity);

            // then
            assertThat(users.count()).isEqualTo(1);
            var user = users.findById(result.userId()).orElseThrow();
            assertThat(user.getProvider()).isEqualTo(identity.provider());
            assertThat(user.getSubject()).isEqualTo(identity.subject());
            assertThat(user.getEmail()).isEqualTo(identity.email());
            assertThat(user.getName()).isEqualTo(identity.name());
            assertThat(user.getStatus()).isEqualTo(UserStatus.APPLIED);
            assertThat(user.getCreditBalance()).isEqualTo(credit.monthlyAmount());
            assertThat(user.getApprovedAt()).isNull();
            assertThat(user.getCreditRefillAt()).isNull();
            assertThat(result).isEqualTo(new AccountPrincipal.Member(user.getId(), user.getEmail(), user.getName(), UserStatus.APPLIED));
            assertThat(entries.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.getId()).isPositive();
                assertThat(entry.getUser()).isSameAs(user);
                assertThat(entry.getType()).isEqualTo(CreditEntryType.REFILL);
                assertThat(entry.getAmount()).isEqualTo(credit.monthlyAmount());
            });
        });
    }

    @ParameterizedTest(name = "기존 상태 {0}")
    @EnumSource(UserStatus.class)
    @DisplayName("회원 ID로 다시 신청하면 현재 상태와 잔액과 신원을 보존한다")
    void preservesExistingMember(UserStatus status) {
        assertExistingAccountPreserved(status, false);
    }

    @ParameterizedTest(name = "기존 상태 {0}")
    @EnumSource(UserStatus.class)
    @DisplayName("옛 신청 전 세션으로 다시 신청해도 현재 회원과 채움 기록을 바꾸지 않는다")
    void preservesExistingIdentity(UserStatus status) {
        assertExistingAccountPreserved(status, true);
    }

    private void assertExistingAccountPreserved(UserStatus status, boolean identitySession) {
        runner.run(context -> {
            // given
            var users = new FakeUserRepository();
            var entries = new FakeCreditEntryRepository();
            var existing = users.save(UserFixtures.withStatus(UserFixtures.user("google", "stable-sub", "old@example.com"), status));
            var service = new ApplyService(users, entries, context.getBean(CreditProperties.class));
            AccountPrincipal principal = identitySession
                    ? new AccountPrincipal.NotApplied("google", "stable-sub", "changed@example.com", "바뀐 이름")
                    : new AccountPrincipal.Member(existing.getId(), "stale@example.com", "오래된 이름", UserStatus.APPLIED);
            int balance = existing.getCreditBalance();
            var approvedAt = existing.getApprovedAt();
            var refillAt = existing.getCreditRefillAt();

            // when
            var result = service.apply(principal);

            // then
            assertThat(result).isEqualTo(new AccountPrincipal.Member(existing.getId(), "old@example.com", "old", status));
            assertThat(users.count()).isEqualTo(1);
            assertThat(users.findById(existing.getId())).containsSame(existing);
            assertThat(existing.getStatus()).isEqualTo(status);
            assertThat(existing.getEmail()).isEqualTo("old@example.com");
            assertThat(existing.getName()).isEqualTo("old");
            assertThat(existing.getCreditBalance()).isEqualTo(balance);
            assertThat(existing.getApprovedAt()).isEqualTo(approvedAt);
            assertThat(existing.getCreditRefillAt()).isEqualTo(refillAt);
            assertThat(entries.entries()).isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CreditProperties.class)
    static class PropertiesConfiguration { }
}
