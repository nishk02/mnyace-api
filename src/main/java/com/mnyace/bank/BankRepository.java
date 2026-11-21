package com.mnyace.bank;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankRepository extends JpaRepository<Bank, String> {
    List<Bank> findByActiveTrueOrderByDisplayOrderAscNameAsc();
}
