package com.example.currency;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class RateController {

    private final RateService rateService;
    private final MeterRegistry meterRegistry;

    public RateController(RateService rateService, MeterRegistry meterRegistry) {
        this.rateService = rateService;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping("/rates/{code}")
    public Map<String, Object> getRate(@PathVariable String code) {
        String upper = code.toUpperCase();

        // Метрика: сколько раз запрашивали конкретную валюту
        meterRegistry.counter("api_currency_requests_total",
                "currency", upper).increment();

        Optional<CurrencyRate> opt = rateService.getLatest(upper);

        Map<String, Object> response = new HashMap<>();
        if (opt.isPresent()) {
            CurrencyRate rate = opt.get();
            response.put("currency", rate.getCurrencyCode());
            response.put("name", rate.getCurrencyName());
            response.put("rate", rate.getRate());
            response.put("fetchedAt", rate.getFetchedAt().toString());
            response.put("status", "ok");
        } else {
            response.put("currency", upper);
            response.put("status", "not_found");
            response.put("message", "No data yet. Try again in a minute.");
        }
        return response;
    }

    @GetMapping("/rates")
    public Map<String, Object> getAllRates() {
        meterRegistry.counter("api_currency_requests_total", "currency", "ALL").increment();

        Map<String, Object> result = new HashMap<>();
        for (String code : new String[]{"USD", "EUR", "KRW", "CNY", "JPY"}) {
            rateService.getLatest(code).ifPresent(r -> result.put(code, r.getRate()));
        }
        return result;
    }

    @GetMapping("/refresh")
    public String refresh() {
        rateService.fetchAndStore();
        return "OK";
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
