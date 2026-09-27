package com.example.currency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CbrClient {

    private static final Set<String> WANTED = Set.of("USD", "EUR", "KRW", "CNY", "JPY");

    private final RestTemplate restTemplate;
    private final MeterRegistry meterRegistry;
    private final String cbrUrl;

    public CbrClient(RestTemplate restTemplate,
                     MeterRegistry meterRegistry,
                     @Value("${CBR_URL:https://www.cbr.ru/scripts/XML_daily.asp}") String cbrUrl) {
        this.restTemplate = restTemplate;
        this.meterRegistry = meterRegistry;
        this.cbrUrl = cbrUrl;
    }

    public List<CurrencyRate> fetchRates() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Получаем ответ как byte[], чтобы не поломать кодировку windows-1251
            byte[] bytes = restTemplate.getForObject(cbrUrl, byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new RuntimeException("Empty response from CBR");
            }

            List<CurrencyRate> result = parseXml(bytes);

            meterRegistry.counter("cbr_api_requests_total", "status", "200").increment();
            return result;

        } catch (Exception e) {
            meterRegistry.counter("cbr_api_requests_total", "status", "error").increment();
            throw new RuntimeException("CBR fetch failed: " + e.getMessage(), e);
        } finally {
            sample.stop(meterRegistry.timer("cbr_api_request_duration_seconds"));
        }
    }

    private List<CurrencyRate> parseXml(byte[] xmlBytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        // DocumentBuilder сам определяет кодировку из <?xml encoding="windows-1251"?>
        Document doc = builder.parse(new ByteArrayInputStream(xmlBytes));
        doc.getDocumentElement().normalize();

        NodeList valutes = doc.getElementsByTagName("Valute");
        List<CurrencyRate> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < valutes.getLength(); i++) {
            Element valute = (Element) valutes.item(i);

            String code = valute.getElementsByTagName("CharCode").item(0).getTextContent();
            if (!WANTED.contains(code)) continue;

            String name = valute.getElementsByTagName("Name").item(0).getTextContent();
            String valueStr = valute.getElementsByTagName("Value").item(0).getTextContent();
            String nominalStr = valute.getElementsByTagName("Nominal").item(0).getTextContent();

            int nominal = Integer.parseInt(nominalStr.trim());
            double value = Double.parseDouble(valueStr.replace(",", ".").trim());

            // ЦБ публикует JPY и KRW за 100 единиц, остальные за 1.
            // Приводим к курсу за 1 единицу.
            double ratePerOne = value / nominal;

            result.add(new CurrencyRate(code, name, ratePerOne, now));
        }

        return result;
    }
}
