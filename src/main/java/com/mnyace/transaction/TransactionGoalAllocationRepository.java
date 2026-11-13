package com.mnyace.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface TransactionGoalAllocationRepository extends JpaRepository<TransactionGoalAllocation, String> {
    List<TransactionGoalAllocation> findByTransactionId(String transactionId);

    /** Account deletion: one statement, not one per row. Native, so it needs no entity loading. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM transaction_goal_allocations WHERE transaction_id IN "
            + "(SELECT id FROM transactions WHERE user_id = :userId)", nativeQuery = true)
    int deleteAllByUser(@Param("userId") String userId);
    List<TransactionGoalAllocation> findByTransactionIdIn(List<String> transactionIds);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM TransactionGoalAllocation a " +
            "WHERE a.transaction.user.id = :userId " +
            "AND a.transaction.date >= :from " +
            "AND a.transaction.date <= :to")
    BigDecimal sumByUserIdAndDateRange(
            @Param("userId") String userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM TransactionGoalAllocation a " +
            "WHERE a.transaction.user.id = :userId " +
            "AND a.transaction.calendarYear = :year " +
            "AND a.transaction.calendarMonth = :month " +
            "AND a.goal.status != 'COMPLETED' " +
            "AND a.goal.startDate <= :today")
    BigDecimal sumByUserIdAndCalendarMonth(
            @Param("userId") String userId,
            @Param("year") int year,
            @Param("month") int month,
            @Param("today") LocalDate today);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM TransactionGoalAllocation a " +
            "WHERE a.goal.id = :goalId " +
            "AND a.transaction.calendarYear = :year " +
            "AND a.transaction.calendarMonth = :month")
    BigDecimal sumByGoalIdAndCalendarMonth(
            @Param("goalId") String goalId,
            @Param("year") int year,
            @Param("month") int month);
}