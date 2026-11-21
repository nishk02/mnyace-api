package com.mnyace.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.mnyace.auth.User;
import com.mnyace.auth.UserRepository;
import com.mnyace.goal.Goal;
import com.mnyace.goal.GoalRepository;
import com.mnyace.shared.exception.ApiErrorCodes;
import com.mnyace.shared.exception.ApiException;
import com.mnyace.transaction.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransactionService transactionService;
    private final GoalRepository goalRepository;
    private final GoalAllocationService goalAllocationService;
    private final AccountBankResolver bankResolver;

    public List<AccountResponse> getAccounts(String userId) {
        return mapAccounts(accountRepository.findByUserIdAndActiveTrue(userId), userId);
    }

    public AccountSummaryResponse getAccountsSummary(String userId, String type) {
        List<AccountResponse> accounts = type != null
                ? mapAccounts(accountRepository.findByUserIdAndTypeAndActiveTrue(userId, type), userId)
                : getAccounts(userId);
        BigDecimal totalBalance = accounts.stream()
                .map(AccountResponse::currentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AccountSummaryResponse(totalBalance, accounts);
    }

    @Transactional
    public AccountResponse createAccount(String userId, CreateAccountRequest request) {
        User user = getUser(userId);

        AccountBankResolver.Resolved bank = bankResolver.resolve(
                request.type(), request.bankCode(), request.customBank(), request.name(), null);
        String name = bank.name() != null ? bank.name() : request.name();
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Account name is required", null);
        }
        // a listed bank's name is shared by design, so a second account at the same bank is allowed
        if (!bank.listed() && accountRepository.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw ApiException.conflict("An account name '" + name + "' already exists");
        }

        if ("BANK".equals(request.type()) && (request.accountSuffix() == null || request.accountSuffix().isBlank())) {
            throw ApiException.badRequest("VALIDATION_ERROR", "accountSuffix is required for a BANK account", null);
        }
        List<String> cardSuffixes = request.cardSuffixes() != null ? request.cardSuffixes() : List.of();
        AccountSuffixes.validate(request.type(), request.accountSuffix(), cardSuffixes, otherActiveAccounts(userId, null));

        Account account = new Account();

        account.setUser(user);
        account.setName(name);
        account.setType(request.type());
        account.setBankCode(bank.bankCode());
        account.setCustomBank(bank.customBank());
        account.setAccountSuffix(request.accountSuffix());
        account.setCardSuffixes(List.copyOf(cardSuffixes));
        account.setCurrentBalance(request.currentBalance() != null ? request.currentBalance() : BigDecimal.ZERO);
        account.setCurrency("INR");
        account.setColorKey(colorKeyFor(bank, request.colorKey(), null, otherActiveAccounts(userId, null)));

        Account savedAccount = accountRepository.save(account);

        // BR-01: auto-create SETTLEMENT transaction for opening balance
        if (savedAccount.getCurrentBalance().compareTo(BigDecimal.ZERO) > 0) {
            transactionService.createOpeningBalanceSettlement(savedAccount, user);
        }

        return AccountResponse.from(savedAccount, false);
    }

    /** Gives a new user their one Cash account, opening at zero. */
    @Transactional
    public void createCashAccount(User user) {
        Account account = new Account();
        account.setUser(user);
        account.setName(Account.CASH_NAME);
        account.setType(Account.CASH_TYPE);
        account.setColorKey(AccountPalette.CASH_KEY);
        accountRepository.save(account);
    }

    public AccountResponse getAccount(String userId, String accountId) {
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));
        boolean goalLinked = goalRepository.existsByAccountIdAndActiveTrueAndStatusNot(accountId, "COMPLETED");
        return AccountResponse.from(account, goalLinked);
    }

    @Transactional
    public AccountResult updateAccount(String userId, String accountId, UpdateAccountRequest request) {
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));

        if (Account.CASH_TYPE.equals(account.getType()) && changesAnythingButBalance(account, request)) {
            throw ApiException.badRequest("The Cash account can only have its balance edited.");
        }

        String newType = request.type() != null ? request.type() : account.getType();
        AccountBankResolver.Resolved bank = bankResolver.resolve(
                newType, request.bankCode(), request.customBank(), request.name(), account);
        // a listed bank keeps the catalog's name; any other account may rename itself
        String newName = bank.listed() ? bank.name() : (bank.name() != null ? bank.name() : request.name());

        if (newName != null
                && !bank.listed()
                && !newName.equalsIgnoreCase(account.getName())
                && accountRepository.existsByUserIdAndNameIgnoreCase(userId, newName)
        ) {
            throw ApiException.conflict("An account named '" + newName + "' already exists");
        }

        String newColorKey = colorKeyFor(bank, request.colorKey(), account.getColorKey(),
                otherActiveAccounts(userId, account.getId()));

        // A BANK account always keeps its digits: omitting them is fine, clearing them is not.
        if ("BANK".equals(newType) && request.accountSuffix() != null && request.accountSuffix().isNull()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "accountSuffix is required for a BANK account", null);
        }
        // Checked before any field is touched: the lookup below would auto-flush a half-changed account into the
        // BANK-only constraint and answer 500 instead of 400.
        String newSuffix = resolveAccountSuffix(request.accountSuffix(), account.getAccountSuffix());
        List<String> newCards = request.cardSuffixes() != null ? request.cardSuffixes() : account.getCardSuffixes();
        AccountSuffixes.validate(newType, newSuffix, newCards, otherActiveAccounts(userId, account.getId()));

        BigDecimal oldBalance = account.getCurrentBalance();

        if (newName != null) {
            account.setName(newName);
        }
        account.setBankCode(bank.bankCode());
        account.setCustomBank(bank.customBank());

        if (request.type() != null) {
            if (!request.type().equals(account.getType())
                    && (Account.CASH_TYPE.equals(request.type()) || Account.CASH_TYPE.equals(account.getType()))) {
                throw ApiException.badRequest("The Cash account's type can't be changed, and no other account can become Cash.");
            }
            account.setType(request.type());
        }

        account.setColorKey(newColorKey);

        account.setAccountSuffix(newSuffix);
        account.setCardSuffixes(List.copyOf(newCards));

        boolean balanceChanged = request.currentBalance() != null
                && request.currentBalance().compareTo(oldBalance) != 0;

        boolean allocationsSupplied = request.goalAllocations() != null && !request.goalAllocations().isEmpty();
        List<GoalAllocationItem> reduction = List.of();
        String info = null;

        if (balanceChanged) {
            BigDecimal newBalance = request.currentBalance();
            List<Goal> activeGoals = goalAllocationService.activeGoalsOn(account);
            BigDecimal earmarked = activeGoals.stream()
                    .map(Goal::getCurrentProgress)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (newBalance.compareTo(earmarked) < 0) {
                BigDecimal shortfall = earmarked.subtract(newBalance);

                InsufficientFreeBalanceDetails details = goalAllocationService.buildBalanceCorrectionDetails(account, newBalance);

                if (details.availableGoals().isEmpty()) {
                    throw ApiException.badRequest("Account balance can't be set below ₹0.");
                }

                if (request.goalAllocations() == null || request.goalAllocations().isEmpty()) {
                    throw ApiException.badRequest(
                            ApiErrorCodes.GOAL_ALLOCATION_SHORTFALL,
                            "This balance is ₹" + formatAmount(shortfall) + " below what's currently earmarked across linked " +
                                    "goals (free balance: ₹" + formatAmount(details.freeBalance()) + "). Choose which goal(s) " +
                                    "should absorb the ₹" + formatAmount(shortfall) + " reduction, or increase the balance instead.",
                            details);
                }

                BigDecimal allocatedTotal = request.goalAllocations().stream()
                        .map(GoalAllocationItem::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                if (allocatedTotal.compareTo(shortfall) != 0) {
                    String verb = allocatedTotal.compareTo(shortfall) < 0 ? "fall short of" : "exceed";
                    throw ApiException.badRequest(
                            "Selected goal reductions (₹" + formatAmount(allocatedTotal) + ") " + verb +
                                    " the shortfall (₹" + formatAmount(shortfall) + "). They must add up to exactly this amount.");
                }

                reduction = request.goalAllocations();
            } else if (allocationsSupplied) {
                BigDecimal selectedTotal = request.goalAllocations().stream()
                        .map(GoalAllocationItem::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                info = "The corrected balance (₹" + formatAmount(newBalance) + ") still covers everything currently " +
                        "earmarked across your goals, so the ₹" + formatAmount(selectedTotal) +
                        " you selected to reduce wasn't needed — nothing was drawn from any goal.";
            }

            account.setCurrentBalance(newBalance);
        } else if (allocationsSupplied) {
            info = "The account balance didn't change, so there was nothing to reconcile — the goal reduction you selected wasn't applied.";
        }

        Account savedAccount = accountRepository.save(account);

        if (balanceChanged) {
            // BR-02: auto-create SETTLEMENT transaction for balance correction
            Transaction settlement = transactionService.createBalanceCorrectionSettlement(
                    savedAccount, getUser(userId), oldBalance, request.currentBalance());

            if (!reduction.isEmpty()) {
                goalAllocationService.applyAllocations(settlement, reduction, GoalAllocationDirection.DECREASE);
            }
        }

        return new AccountResult(AccountResponse.from(savedAccount, false), info);
    }

    @Transactional
    public void deleteAccount(String userId, String accountId) {
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));

        if (Account.CASH_TYPE.equals(account.getType())) {
            throw ApiException.badRequest("The Cash account can't be deleted.");
        }

        // frees the digits for another account; matching only ever looks at active ones
        account.setAccountSuffix(null);
        account.setCardSuffixes(List.of());
        account.setActive(false);
        accountRepository.save(account);
    }

    /**
     * The colour key an account ends up with. A listed bank takes its colour from the catalog, so it holds no key and
     * refuses one; any other account picks from the palette, omitting the key keeps the stored one, and an account
     * with none is given the palette colour the user's other accounts use least.
     */
    private String colorKeyFor(AccountBankResolver.Resolved bank, String requested, String stored, List<Account> others) {
        if (bank.listed()) {
            if (requested != null) {
                throw ApiException.badRequest("VALIDATION_ERROR",
                        "colorKey is not accepted for a bank from the list; its colour comes from the list", null);
            }
            return null;
        }
        if (requested != null && !AccountPalette.contains(requested)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "colorKey is not one of the account colours", null);
        }
        String key = requested != null ? requested : stored;
        return key != null ? key : leastUsedColour(others);
    }

    /** An account left without a colour gets the palette colour the user's other accounts use least. */
    private String leastUsedColour(List<Account> others) {
        return AccountPalette.keys().stream()
                .min(java.util.Comparator.comparingLong(
                        key -> others.stream().filter(a -> key.equals(a.getColorKey())).count()))
                .orElseThrow();
    }

    /** Omitted leaves the stored value, an explicit null clears it, text replaces it. */
    private String resolveAccountSuffix(JsonNode requested, String stored) {
        if (requested == null) {
            return stored;
        }
        if (requested.isNull()) {
            return null;
        }
        if (!requested.isTextual()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "accountSuffix must be 3 or 4 digits", null);
        }
        return requested.asText();
    }

    private List<Account> otherActiveAccounts(String userId, String exceptId) {
        return accountRepository.findByUserIdAndActiveTrue(userId).stream()
                .filter(a -> !a.getId().equals(exceptId))
                .toList();
    }

    private List<AccountResponse> mapAccounts(List<Account> accounts, String userId) {
        Set<String> goalLinkedAccountIds = goalRepository
                .findByUserIdAndActiveTrueOrderByDisplayOrderAsc(userId)
                .stream()
                .filter(g -> !"COMPLETED".equals(g.getStatus()))
                .map(g -> g.getAccount().getId())
                .collect(Collectors.toSet());

        return accounts.stream()
                .map(a -> AccountResponse.from(a, goalLinkedAccountIds.contains(a.getId())))
                .toList();
    }

    private User getUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    private String formatAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    /** The edit form resends every field, so only a value that differs from the stored one counts as a change. */
    private boolean changesAnythingButBalance(Account account, UpdateAccountRequest request) {
        return (request.name() != null && !request.name().equals(account.getName()))
                || (request.type() != null && !request.type().equals(account.getType()))
                || (request.colorKey() != null && !request.colorKey().equals(account.getColorKey()))
                || request.bankCode() != null
                || Boolean.TRUE.equals(request.customBank());
    }
}
