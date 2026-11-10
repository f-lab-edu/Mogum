package com.mogum.account.service;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.User;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.config.CreditProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplyService {
    private final UserRepository users;
    private final CreditEntryRepository entries;
    private final CreditProperties credit;

    public ApplyService(UserRepository users, CreditEntryRepository entries, CreditProperties credit) {
        this.users = users;
        this.entries = entries;
        this.credit = credit;
    }

    @Transactional
    public AccountPrincipal.Member apply(AccountPrincipal principal) {
        User user = switch (principal) {
            case AccountPrincipal.Member member -> users.findById(member.userId())
                    .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
            case AccountPrincipal.NotApplied identity -> {
                // 조회 전에 잠근다. 기다린 요청은 앞선 트랜잭션이 커밋한 행을 읽는다.
                users.lockIdentity(identity.provider(), identity.subject());
                yield users.findByProviderAndSubject(identity.provider(), identity.subject()).orElseGet(() -> {
                    var applied = users.save(User.apply(identity.provider(), identity.subject(), identity.email(),
                            identity.name(), credit.monthlyAmount()));
                    entries.save(CreditEntry.refill(applied, credit.monthlyAmount()));
                    return applied;
                });
            }
        };
        return new AccountPrincipal.Member(user.getId(), user.getEmail(), user.getName(), user.getStatus());
    }
}
