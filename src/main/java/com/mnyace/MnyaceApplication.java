package com.mnyace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

// Sign-in is our own JWT flow; the default in-memory user, and its password in the startup log, are unused.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableJpaAuditing
@EnableMethodSecurity
@EnableScheduling
public class MnyaceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MnyaceApplication.class, args);
    }
}
