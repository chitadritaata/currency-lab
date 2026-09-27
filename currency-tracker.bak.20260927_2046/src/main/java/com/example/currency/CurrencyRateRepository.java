package com.example.currency;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CurrencyRateRepository extends JpaRepository<CurrencyRate, Long> {

    Optional<CurrencyRate> findTopByCurrencyCodeOrderByFetchedAtDesc(String currencyCode);
}
