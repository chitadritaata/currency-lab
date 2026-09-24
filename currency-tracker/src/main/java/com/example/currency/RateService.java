package com.example.currency;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RateService {

    private final CbrClient cbrClient;
    private final CurrencyRateRepository repository;
    private final MeterRegistry meterRegistry;

    // Держим текущие значения в памяти, чтобы gauge всегда имел к чему обратиться
    private final Map<String, AtomicReference<Double>> liveRates = new ConcurrentHashMap<>();

    public RateService(CbrClient cbrClient,
                       CurrencyRateRepository repository,
                       MeterRegistry meterRegistry) {
        this.cbrClient = cbrClient;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void scheduledFetch() {
        fetchAndStore();
    }

    @Transactional
    public void fetchAndStore() {
        List<CurrencyRate> rates = cbrClient.fetchRates();

        for (CurrencyRate rate : rates) {
            repository.save(rate);

            meterRegistry.counter("currency_rates_stored_total",
                    "currency", rate.getCurrencyCode()).increment();

            // Получаем или создаём AtomicReference для этой валюты
            AtomicReference<Double> ref = liveRates.computeIfAbsent(
                    rate.getCurrencyCode(),
                    code -> {
                        // При первом появлении валюты регистрируем gauge
                        AtomicReference<Double> newRef = new AtomicReference<>(0.0);
                        Gauge.builder("currency_rate_rub", newRef, AtomicReference::get)
                                .tags("currency", code,
                                      "name", rate.getCurrencyName())
                                .description("Currency rate in RUB")
                                .register(meterRegistry);
                        return newRef;
                    }
            );

            // Обновляем значение
            ref.set(rate.getRate());
        }

        System.out.println(">>> Stored " + rates.size() + " currency rates");
    }

    public Optional<CurrencyRate> getLatest(String code) {
        return repository.findTopByCurrencyCodeOrderByFetchedAtDesc(code.toUpperCase());
    }
}
