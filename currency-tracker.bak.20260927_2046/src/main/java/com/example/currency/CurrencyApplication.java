package com.example.currency;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
@EnableScheduling
public class CurrencyApplication {

    public static void main(String[] args) {
        SpringApplication.run(CurrencyApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(15))
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .defaultHeader("Accept", "*/*")
                .defaultHeader("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .build();
    }

    @Bean
    public CommandLineRunner initialFetch(RateService rateService) {
        return args -> {
            try {
                System.out.println(">>> Initial fetch from CBR...");
                rateService.fetchAndStore();
                System.out.println(">>> Initial fetch done");
            } catch (Exception e) {
                System.err.println(">>> Initial fetch failed: " + e.getMessage());
            }
        };
    }
}
