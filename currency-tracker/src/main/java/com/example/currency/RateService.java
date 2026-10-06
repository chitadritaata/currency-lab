package com.example.currency;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RateService {

    private static final Logger log = LoggerFactory.getLogger(RateService.class);

    private final CbrClient cbrClient;
    private final CurrencyRateRepository repository;
    private final MeterRegistry meterRegistry;

    // Хранит последние значения курсов для gauge
    private final Map<String, AtomicReference<Double>> lastRates = new ConcurrentHashMap<>();

    public RateService(CbrClient cbrClient,
                       CurrencyRateRepository repository,
                       MeterRegistry meterRegistry) {
        this.cbrClient = cbrClient;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void onStartup() {
        log.info(">>> Initial fetch from CBR...");
        try {
            fetchAndStore();
            log.info(">>> Initial fetch completed");
        } catch (Exception e) {
            log.error(">>> Initial fetch failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedRate = 3600000)
    public void scheduledFetch() {
        log.info(">>> Scheduled fetch from CBR...");
        try {
            fetchAndStore();
            log.info(">>> Scheduled fetch completed");
        } catch (Exception e) {
            log.error(">>> Scheduled fetch failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    public void fetchAndStore() {
        Map<String, Object> resp = cbrClient.fetchRates();

        if (!"success".equals(resp.get("result"))) {
            throw new RuntimeException("API вернул: " + resp.get("result"));
        }

        Map<String, Object> rates = (Map<String, Object>) resp.get("rates");
        LocalDateTime now = LocalDateTime.now();

        for (String code : List.of("USD", "EUR", "KRW", "CNY", "JPY")) {
            Object rateObj = rates.get(code);
            if (rateObj == null) continue;

            double value = ((Number) rateObj).doubleValue();
            if (value == 0) continue;

            // open.er-api.com отдаёт: 1 RUB = X валюты
            // нам надо: 1 валюта = Y RUB → инвертируем
            double rubPerUnit = 1.0 / value;
            String name = nameFor(code);

            // 1. Сохраняем в БД
            CurrencyRate entity = new CurrencyRate(code, name, rubPerUnit, now);
            repository.save(entity);

            // 2. Обновляем счётчик
            meterRegistry.counter("currency_rates_stored_total",
                    "currency", code).increment();

            // 3. Регистрируем или обновляем gauge
            AtomicReference<Double> ref = lastRates.computeIfAbsent(code, k -> {
                AtomicReference<Double> r = new AtomicReference<>(rubPerUnit);
                Gauge.builder("currency_rate_rub", r, AtomicReference::get)
                        .tag("currency", code)
                        .tag("name", name)
                        .strongReference(true)
                        .register(meterRegistry);
                return r;
            });
            ref.set(rubPerUnit);
        }
    }

    public Optional<CurrencyRate> getLatest(String code) {
        return repository.findTopByCurrencyCodeOrderByFetchedAtDesc(code.toUpperCase());
    }

    private String nameFor(String code) {
        return switch (code) {
            case "USD" -> "Доллар США";
            case "EUR" -> "Евро";
            case "KRW" -> "Вона";
            case "CNY" -> "Юань";
            case "JPY" -> "Иена";
            default -> code;
        };
    }
}
