package com.mnyace.account;

import com.mnyace.auth.User;
import com.mnyace.bank.Bank;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Account {
    public static final String CASH_TYPE = "CASH";
    public static final String CASH_NAME = "Cash";

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @Column(name = "current_balance", nullable = false, columnDefinition = "REAL")
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(nullable = false)
    private String currency = "INR";

    @Column(name = "color_key")
    private String colorKey;

    @Column(name = "account_suffix")
    private String accountSuffix;

    @Column(name = "bank_code")
    private String bankCode;

    // read-only view of the catalog row, so a listed bank's colour follows the catalog; a custom code has none
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "bank_code", referencedColumnName = "code", insertable = false, updatable = false)
    @NotFound(action = NotFoundAction.IGNORE)
    private Bank bank;

    @Column(name = "custom_bank", nullable = false, columnDefinition = "INTEGER")
    private boolean customBank = false;

    @Convert(converter = CardSuffixesConverter.class)
    @Column(name = "card_suffixes", nullable = false)
    private List<String> cardSuffixes = List.of();

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "is_active", nullable = false, columnDefinition = "INTEGER")
    private boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** A listed bank's colour on a white page, from the catalog so it follows it; null for any other account. */
    public String getColorLight() {
        return !customBank && bank != null ? bank.getColorLight() : null;
    }

    /** A listed bank's colour on a #121212 page; null for any other account. */
    public String getColorDark() {
        return !customBank && bank != null ? bank.getColorDark() : null;
    }
}
