package com.mnyace.bank;

import com.mnyace.shared.dto.ApiResponse;
import com.mnyace.shared.security.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/banks")
@RequiredArgsConstructor
public class BankController extends BaseController {
    private final BankRepository banks;

    /** The list changes only in a migration, so a day-long private cache and an ETag keep the app from refetching it. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<BankResponse>>> getBanks(WebRequest request) {
        List<BankResponse> list = banks.findByActiveTrueOrderByDisplayOrderAscNameAsc().stream()
                .map(BankResponse::from)
                .toList();
        String etag = "\"" + Integer.toHexString(list.hashCode()) + "\"";
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .eTag(etag)
                .body(ApiResponse.success(list));
    }
}
