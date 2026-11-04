package com.mnyace.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapRunnerTest {
    private UserRepository users;
    private AdminBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hash");
        runner = new AdminBootstrapRunner(users, encoder);
        ReflectionTestUtils.setField(runner, "adminEmail", "Admin@Test.Local");
        ReflectionTestUtils.setField(runner, "adminPassword", "AdminPass123!");
    }

    @Test
    void createsTheAdminWithTheConfiguredName() {
        ReflectionTestUtils.setField(runner, "adminFirstName", "Pat");
        ReflectionTestUtils.setField(runner, "adminLastName", "Kumar");

        runner.run();

        User saved = saved();
        assertThat(saved.getFirstName()).isEqualTo("Pat");
        assertThat(saved.getLastName()).isEqualTo("Kumar");
        assertThat(saved.getEmail()).isEqualTo("admin@test.local");
        assertThat(saved.getRole()).isEqualTo("ADMIN");
    }

    @Test
    void blankNames_fallBackToAdminUser() {
        ReflectionTestUtils.setField(runner, "adminFirstName", "");
        ReflectionTestUtils.setField(runner, "adminLastName", "   ");

        runner.run();

        assertThat(saved().getFirstName()).isEqualTo("Admin");
        assertThat(saved().getLastName()).isEqualTo("User");
    }

    @Test
    void anExistingAdmin_isLeftAlone() {
        ReflectionTestUtils.setField(runner, "adminFirstName", "Pat");
        ReflectionTestUtils.setField(runner, "adminLastName", "Kumar");
        when(users.existsByRole("ADMIN")).thenReturn(true);

        runner.run();

        verify(users, never()).save(any());
    }

    private User saved() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        return captor.getValue();
    }
}
