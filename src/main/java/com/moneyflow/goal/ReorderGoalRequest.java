package com.moneyflow.goal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReorderGoalRequest(
        @NotNull(message = "Items are required")
        @Size(max = 200, message = "Too many items")
        @Valid
        List<ReorderItem> items
) {
    public record ReorderItem(@NotBlank(message = "Goal id is required") String id, int displayOrder) {}
}
