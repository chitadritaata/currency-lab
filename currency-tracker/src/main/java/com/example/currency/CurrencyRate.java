package com.example.currency;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "currency_rates")
public class CurrencyRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String currencyCode;

    @Column(nullable = false)
    private String currencyName;

    @Column(nullable = false)
    private Double rate; // сколько рублей за 1 единицу валюты

    @Column(nullable = false)
    private LocalDateTime fetchedAt;

    public CurrencyRate() {}

    public CurrencyRate(String code, String name, Double rate, LocalDateTime fetchedAt) {
        this.currencyCode = code;
        this.currencyName = name;
        this.rate = rate;
        this.fetchedAt = fetchedAt;
    }

    // геттеры и сеттеры
    public Long getId() { return id; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public String getCurrencyName() { return currencyName; }
    public void setCurrencyName(String currencyName) { this.currencyName = currencyName; }
    public Double getRate() { return rate; }
    public void setRate(Double rate) { this.rate = rate; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
}
