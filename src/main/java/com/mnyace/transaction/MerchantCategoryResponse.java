package com.mnyace.transaction;

import java.util.List;

/** The category a user most often gives a merchant; {@code count} is how many transactions chose it. */
public record MerchantCategoryResponse(String merchant, String merchantKey, String categoryId, int count) {
    public record Listing(List<MerchantCategoryResponse> merchantCategories) {
    }
}
