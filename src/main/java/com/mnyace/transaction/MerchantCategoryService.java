package com.mnyace.transaction;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What category the user usually gives each merchant, read live from their transactions (BR-34), so a corrected
 * category changes what is learned and a deleted transaction stops counting. Plain SQL keeps the grouping in
 * the database and the tie-break rules in one place.
 */
@Service
@RequiredArgsConstructor
public class MerchantCategoryService {
    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 1000;

    private final JdbcTemplate jdbc;

    public MerchantCategoryResponse.Listing getMerchantCategories(String userId, FlowType flowType, Integer limit) {
        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        List<Object> args = new ArrayList<>();
        args.add(userId);
        String typeFilter = "";
        if (flowType != null) {
            List<TransactionType> types = flowType.getTypes();
            typeFilter = " AND t.type IN (" + String.join(",", types.stream().map(t -> "?").toList()) + ")";
            types.forEach(t -> args.add(t.name()));
        }

        // Inactive and internal categories are left out here, so a merchant whose top choice is gone falls back to its next best
        List<Choice> choices = jdbc.query(
                "SELECT t.merchant_key, t.category_id, COUNT(*), MAX(t.updated_at) "
                        + "FROM transactions t JOIN categories c ON c.id = t.category_id "
                        + "WHERE t.user_id = ? AND t.merchant_key IS NOT NULL AND c.is_active = 1 AND c.is_internal = 0"
                        + typeFilter + " GROUP BY t.merchant_key, t.category_id",
                (rs, i) -> new Choice(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)),
                args.toArray());

        Map<String, Choice> best = new HashMap<>();
        Map<String, String> lastUsed = new HashMap<>();
        for (Choice choice : choices) {
            best.merge(choice.merchantKey(), choice, (a, b) -> beats(b, a) ? b : a);
            lastUsed.merge(choice.merchantKey(), choice.lastUsed(), (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        if (best.isEmpty()) {
            return new MerchantCategoryResponse.Listing(List.of());
        }

        // The spelling shown is the most recently stored one; SQLite takes bare columns from the MAX row
        Map<String, String> spelling = new HashMap<>();
        jdbc.query("SELECT t.merchant_key, t.merchant, MAX(t.updated_at) FROM transactions t "
                        + "WHERE t.user_id = ? AND t.merchant_key IS NOT NULL" + typeFilter + " GROUP BY t.merchant_key",
                rs -> {
                    spelling.put(rs.getString(1), rs.getString(2));
                },
                args.toArray());

        return new MerchantCategoryResponse.Listing(best.values().stream()
                .sorted(Comparator.comparing((Choice c) -> lastUsed.get(c.merchantKey())).reversed()
                        .thenComparing(Choice::merchantKey))
                .limit(cap)
                .map(c -> new MerchantCategoryResponse(
                        spelling.getOrDefault(c.merchantKey(), c.merchantKey()), c.merchantKey(), c.categoryId(), c.count()))
                .toList());
    }

    /** Higher count wins; on a tie, the category touched most recently. */
    private static boolean beats(Choice challenger, Choice current) {
        if (challenger.count() != current.count()) {
            return challenger.count() > current.count();
        }
        return challenger.lastUsed().compareTo(current.lastUsed()) > 0;
    }

    private record Choice(String merchantKey, String categoryId, int count, String lastUsed) {
    }
}
