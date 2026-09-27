package com.example.currency;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitConfig {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public Bucket resolveBucket(String key) {
        return buckets.computeIfAbsent(key, k -> createBucket());
    }

    private Bucket createBucket() {
        // 20 токенов в ведре, пополнение 10 в секунду.
        // Для тренировки DDoS — этого хватит, чтобы увидеть 429.
        Bandwidth limit = Bandwidth.classic(20, Refill.greedy(10, Duration.ofSeconds(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    public void resetAll() {
        buckets.clear();
    }
}
