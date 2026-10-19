package com.mogum;

import com.mogum.core.TransactionRunner;
import com.mogum.core.account.Login;
import com.mogum.core.account.UserRepository;
import com.mogum.core.account.UserIdentityRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    @Bean
    public Login login(UserRepository repository, UserIdentityRepository identities, TransactionRunner transactions,
                       Clock clock, AuthProperties properties) {
        return new Login(repository, identities, transactions, clock, properties.adminEmails());
    }
}
