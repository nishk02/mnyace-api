package com.mnyace.goal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoalRepository extends JpaRepository<Goal, String> {
    List<Goal> findByUserIdAndActiveTrueOrderByDisplayOrderAsc(String userId);
    /** Account deletion: one statement, not one per row. Native, so it needs no entity loading. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM goals WHERE user_id = :userId", nativeQuery = true)
    int deleteAllByUser(@Param("userId") String userId);

    Optional<Goal> findByIdAndUserId(String id, String userId);

    boolean existsByUserIdAndNameIgnoreCase(String userId, String name);

    List<Goal> findByUserIdAndStatusAndActiveTrue(String userId, String status);

    boolean existsByAccountIdAndActiveTrueAndStatusNot(String accountId, String status);

    Optional<Goal> findByAccountIdAndActiveTrueAndStatusNot(String accountId, String status);

    List<Goal> findByAccountIdAndActiveTrueAndStatusNotOrderByDisplayOrderAsc(String accountId, String status);
}
