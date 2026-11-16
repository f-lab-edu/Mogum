package com.mogum.account.service;

import com.mogum.account.domain.CreditEntry;
import com.mogum.account.domain.User;
import com.mogum.account.repository.CreditEntryRepository;
import com.mogum.account.repository.UserRepository;
import com.mogum.auth.AccountPrincipal;
import com.mogum.common.api.ApiException;
import com.mogum.common.api.ErrorCode;
import com.mogum.config.CreditProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplyService {
    private final UserRepository userRepository;
    private final CreditEntryRepository creditEntryRepository;
    private final CreditProperties creditProperties;

    @Transactional
    public AccountPrincipal.Member apply(AccountPrincipal principal) {
        User user = switch (principal) {
            case AccountPrincipal.Member member -> userRepository.findById(member.userId())
                    .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
            case AccountPrincipal.NotApplied identity -> {
                // 조회 전에 잠근다. 기다린 요청은 앞선 트랜잭션이 커밋한 행을 읽는다.
                userRepository.lockIdentity(identity.provider(), identity.subject());
                yield userRepository.findByProviderAndSubject(identity.provider(), identity.subject()).orElseGet(() -> {
                    var applied = userRepository.save(User.apply(identity.provider(), identity.subject(), identity.email(),
                            identity.name(), creditProperties.monthlyAmount()));
                    creditEntryRepository.save(CreditEntry.refill(applied, creditProperties.monthlyAmount()));
                    return applied;
                });
            }
        };
        return new AccountPrincipal.Member(user.getId(), user.getEmail(), user.getName(), user.getStatus());
    }
}
