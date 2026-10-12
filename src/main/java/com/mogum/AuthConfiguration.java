package com.mogum;

import com.mogum.core.TransactionRunner;
import com.mogum.core.account.GoogleLogin;
import com.mogum.core.account.UserRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    @Bean
    public GoogleLogin googleLogin(UserRepository repository, TransactionRunner transactions,
                                   Clock clock, AuthProperties properties) {
        return new GoogleLogin(repository, transactions, clock, properties.adminEmails());
    }
}
