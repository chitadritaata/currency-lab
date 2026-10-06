package com.example.currency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class CbrClient {

    private final RestTemplate restTemplate;
    private final MeterRegistry meterRegistry;

    public CbrClient(RestTemplate restTemplate, MeterRegistry meterRegistry) {
        this.restTemplate = restTemplate;
        this.meterRegistry = meterRegistry;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchRates() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    "https://open.er-api.com/v6/latest/RUB",
                    HttpMethod.GET, entity, Map.class);

            meterRegistry.counter("external_api_requests_total",
                    "api", "cbr", "status", "200").increment();

            return resp.getBody();

        } catch (HttpStatusCodeException e) {
            String status = String.valueOf(e.getStatusCode().value());
            meterRegistry.counter("external_api_requests_total",
                    "api", "cbr", "status", status).increment();
            meterRegistry.counter("external_api_errors_total",
                    "api", "cbr", "status", status).increment();
            throw new RuntimeException("CBR fetch failed: " + status + ": " + e.getResponseBodyAsString(), e);

        } catch (Exception e) {
            meterRegistry.counter("external_api_requests_total",
                    "api", "cbr", "status", "0").increment();
            meterRegistry.counter("external_api_errors_total",
                    "api", "cbr", "status", "0").increment();
            throw new RuntimeException("CBR fetch failed: " + e.getMessage(), e);

        } finally {
            sample.stop(meterRegistry.timer("external_api_duration_seconds", "api", "cbr"));
        }
    }
}
