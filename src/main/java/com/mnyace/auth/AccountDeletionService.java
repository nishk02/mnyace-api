package com.mnyace.auth;

import com.mnyace.account.Account;
import com.mnyace.account.AccountRepository;
import com.mnyace.goal.Goal;
import com.mnyace.goal.GoalRepository;
import com.mnyace.invite.InviteRepository;
import com.mnyace.shared.exception.ApiException;
import com.mnyace.shared.security.audit.SecurityEvent;
import com.mnyace.shared.security.audit.SecurityEvents;
import com.mnyace.shared.security.ratelimit.RateLimiterService;
import com.mnyace.transaction.Transaction;
import com.mnyace.transaction.TransactionGoalAllocationRepository;
import com.mnyace.transaction.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Hard-deletes a user and everything held under them; the foreign keys have no cascade, so each table is cleared in turn. */
@Service
public class AccountDeletionService {
    private static final int CHUNK = 500;

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final GoalRepository goalRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionGoalAllocationRepository allocationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final InviteRepository inviteRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiterService passwordChangeRateLimiter;
    private final SecurityEvents securityEvents;
    private final EntityManager entityManager;

    public AccountDeletionService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            GoalRepository goalRepository,
            TransactionRepository transactionRepository,
            TransactionGoalAllocationRepository allocationRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordResetRepository passwordResetRepository,
            InviteRepository inviteRepository,
            PasswordEncoder passwordEncoder,
            @Qualifier("passwordChangeRateLimiter") RateLimiterService passwordChangeRateLimiter,
            SecurityEvents securityEvents,
            EntityManager entityManager
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.goalRepository = goalRepository;
        this.transactionRepository = transactionRepository;
        this.allocationRepository = allocationRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.inviteRepository = inviteRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordChangeRateLimiter = passwordChangeRateLimiter;
        this.securityEvents = securityEvents;
        this.entityManager = entityManager;
    }

    @Transactional
    public void deleteAccount(String userId, String password) {
        // Same cap as a password change: a stolen access token must not be able to guess the password here.
        if (!passwordChangeRateLimiter.consume(userId).allowed()) {
            securityEvents.record(SecurityEvent.RATE_LIMITED, userId, "account_delete_user");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Too many attempts. Please wait and try again.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Not authenticated"));

        // 400, not 401: clients treat a 401 as an expired session and would sign the user out for a typo.
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", "Password is incorrect");
        }
        // Invites reference the admin who sent them, so deleting an admin would orphan or erase other people's invites.
        if ("ADMIN".equals(user.getRole())) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_ACCOUNT_PROTECTED",
                    "An admin account cannot be deleted here.");
        }

        List<Transaction> transactions = transactionRepository.findByUserId(userId);
        List<String> transactionIds = transactions.stream().map(Transaction::getId).toList();
        for (int from = 0; from < transactionIds.size(); from += CHUNK) {
            List<String> chunk = transactionIds.subList(from, Math.min(from + CHUNK, transactionIds.size()));
            allocationRepository.deleteAll(allocationRepository.findByTransactionIdIn(chunk));
        }
        flushAndClear();

        transactionRepository.deleteAll(transactions);
        flushAndClear();

        List<Goal> goals = goalRepository.findByUserId(userId);
        goalRepository.deleteAll(goals);
        flushAndClear();

        List<Account> accounts = accountRepository.findByUserId(userId);
        accountRepository.deleteAll(accounts);
        flushAndClear();

        refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUser_Id(userId));
        passwordResetRepository.deleteAll(passwordResetRepository.findAllByUser_Id(userId));
        inviteRepository.deleteAll(inviteRepository.findAllByEmailIgnoreCase(user.getEmail()));
        flushAndClear();

        userRepository.deleteById(userId);
        flushAndClear();

        securityEvents.record(SecurityEvent.ACCOUNT_DELETED, userId, null);
    }

    /** Each table is written out before the next, so rows go in foreign-key order whatever order Hibernate would pick. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
