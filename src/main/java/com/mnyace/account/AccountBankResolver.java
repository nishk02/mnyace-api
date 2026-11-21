package com.mnyace.account;

import com.mnyace.bank.Bank;
import com.mnyace.bank.BankRepository;
import com.mnyace.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Decides an account's bank from a create or update request. A listed bank takes its name from the catalog; an
 * "other" bank (customBank) brings its own code and name, and the code must stay out of the catalog.
 */
@Component
@RequiredArgsConstructor
class AccountBankResolver {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{2,10}");

    private final BankRepository banks;

    /** The bank an account ends up with; {@code name} is null unless the catalog or a custom bank dictates it. */
    record Resolved(String bankCode, boolean customBank, String name) {
        boolean listed() {
            return bankCode != null && !customBank;
        }
    }

    /**
     * @param type   the account's type after this request
     * @param stored the account being edited, or null on create
     */
    Resolved resolve(String type, String bankCode, Boolean customBank, String name, Account stored) {
        String storedCode = stored != null ? stored.getBankCode() : null;
        boolean storedCustom = stored != null && stored.isCustomBank();

        if (!"BANK".equals(type)) {
            if (bankCode != null || Boolean.TRUE.equals(customBank) || storedCode != null) {
                throw invalid("Only a BANK account can have bankCode or customBank");
            }
            return new Resolved(null, false, null);
        }

        if (bankCode == null) {
            if (customBank != null && customBank != storedCustom) {
                throw invalid("bankCode is required with customBank");
            }
            return new Resolved(storedCode, storedCustom, null);
        }

        if (!CODE.matcher(bankCode).matches()) {
            throw invalid("bankCode must be 2 to 10 upper-case letters or digits");
        }
        boolean sameBank = bankCode.equals(storedCode);

        if (Boolean.TRUE.equals(customBank)) {
            String customName = name != null && !name.isBlank() ? name : (storedCustom ? stored.getName() : null);
            if (customName == null || customName.isBlank()) {
                throw invalid("name is required for a bank that is not in the list");
            }
            // an unchanged custom code is let through, so a code added to the catalog later never blocks an edit
            if (!(sameBank && storedCustom) && banks.existsById(bankCode)) {
                throw invalid("That bankCode is reserved by the bank list; pick the bank from the list instead");
            }
            return new Resolved(bankCode, true, customName);
        }

        Bank bank = banks.findById(bankCode).orElseThrow(() -> invalid("bankCode is not in the bank list"));
        // an account keeps a bank that was made inactive later, but nobody new can pick it
        if (!bank.isActive() && !(sameBank && !storedCustom)) {
            throw invalid("bankCode is not in the bank list");
        }
        return new Resolved(bankCode, false, bank.getName());
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("VALIDATION_ERROR", message, null);
    }
}
