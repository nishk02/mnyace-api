package com.mnyace.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, String> {
    List<Account> findByUserIdAndActiveTrue(String userId);
    /** Account deletion: one statement, not one per row. Native, so it needs no entity loading. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM accounts WHERE user_id = :userId", nativeQuery = true)
    int deleteAllByUser(@Param("userId") String userId);

    Optional<Account> findByIdAndUserId(String id, String userId);

    boolean existsByUserIdAndNameIgnoreCase(String userId, String name);

    List<Account> findByUserIdAndTypeAndActiveTrue(String userId, String type);
}