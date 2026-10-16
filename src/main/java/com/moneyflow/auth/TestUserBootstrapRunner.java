package com.moneyflow.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Dev-only: recreates a MEMBER test account after a DB wipe, bypassing invite + OTP.
 * `@Profile("dev")` and a blank-by-default property guard prevent seeding a known password outside local development.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class TestUserBootstrapRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(TestUserBootstrapRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${test-user.bootstrap.email:}")
    private String email;

    @Value("${test-user.bootstrap.password:}")
    private String password;

    @Value("${test-user.bootstrap.first-name:Test}")
    private String firstName;

    @Value("${test-user.bootstrap.last-name:User}")
    private String lastName;

    @Override
    public void run(String... args) {
        if (email.isBlank() || password.isBlank()) {
            return; // not configured
        }
        if (userRepository.existsByEmail(email)) {
            return; // already there - never duplicate or overwrite
        }

        User user = new User();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole("MEMBER");
        userRepository.save(user);

        log.info("Bootstrapped test member account: {}", email);
    }
}