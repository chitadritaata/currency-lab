package com.example.bot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class MyBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final String trackerUrl;
    private final RestTemplate restTemplate = new RestTemplate();
    private final MeterRegistry meterRegistry;

    public MyBot(@Value("${bot.token}") String botToken,
                 @Value("${bot.username}") String botUsername,
                 @Value("${tracker.url}") String trackerUrl,
                 MeterRegistry meterRegistry) {
        super(botToken);
        this.botUsername = botUsername;
        this.trackerUrl = trackerUrl;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    // ============================================================
    // ВХОДЯЩИЕ СОБЫТИЯ ОТ TELEGRAM
    // ============================================================

    @Override
    public void onUpdateReceived(Update update) {

        // ---- Нажатие inline-кнопки ----
        if (update.hasCallbackQuery()) {
            Timer.Sample sample = Timer.start(meterRegistry);
            CallbackQuery cb = update.getCallbackQuery();
            String username = cb.getFrom() != null && cb.getFrom().getUserName() != null
                    ? cb.getFrom().getUserName() : "unknown";

            meterRegistry.counter("bot_callbacks_incoming_total",
                    "user", username,
                    "action", cb.getData()
            ).increment();

            handleCallback(cb);

            String action = cb.getData().startsWith("cur:") ? cb.getData().substring(4) : "menu";
            sample.stop(meterRegistry.timer("bot_user_response_duration_seconds",
                    "action", action));
            return;
        }

        // ---- Текстовое сообщение ----
        if (update.hasMessage() && update.getMessage().hasText()) {
            Timer.Sample sample = Timer.start(meterRegistry);
            String text = update.getMessage().getText();
            Long chatId = update.getMessage().getChatId();
            String username = update.getMessage().getFrom() != null
                    && update.getMessage().getFrom().getUserName() != null
                    ? update.getMessage().getFrom().getUserName() : "unknown";

            meterRegistry.counter("bot_messages_incoming_total",
                    "user", username,
                    "command", text.startsWith("/") ? text : "text"
            ).increment();

            if (text.equals("/start") || text.equals("/menu")) {
                sendMainMenu(chatId);
            } else {
                sendMessage(chatId, "Жми /start");
            }

            sample.stop(meterRegistry.timer("bot_user_response_duration_seconds",
                    "action", "message"));
        }
    }

    // ============================================================
    // МЕНЮ
    // ============================================================

    private void sendMainMenu(Long chatId) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText("💱 Выбери валюту:");
        msg.setReplyMarkup(buildMenu());
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private InlineKeyboardButton btn(String text, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(text);
        b.setCallbackData(data);
        return b;
    }

    private InlineKeyboardMarkup buildMenu() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(btn("🇺🇸 Доллар США", "cur:USD"), btn("🇪🇺 Евро", "cur:EUR")));
        rows.add(List.of(btn("🇰🇷 Вона", "cur:KRW"), btn("🇨🇳 Юань", "cur:CNY")));
        rows.add(List.of(btn("🇯🇵 Йена", "cur:JPY")));
        rows.add(List.of(btn("📊 Все курсы", "cur:ALL")));
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup buildBackMenu() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(btn("◀️ Назад", "menu:refresh")));
        markup.setKeyboard(rows);
        return markup;
    }

    // ============================================================
    // ОБРАБОТКА НАЖАТИЙ
    // ============================================================

    private void handleCallback(CallbackQuery cb) {
        String data = cb.getData();
        Long chatId = cb.getMessage().getChatId();
        Integer msgId = cb.getMessage().getMessageId();

        String username = cb.getFrom() != null && cb.getFrom().getUserName() != null
                ? cb.getFrom().getUserName() : "unknown";

        answerCallback(cb.getId());

        if (data.equals("menu:refresh")) {
            editMessage(chatId, msgId, "💱 Выбери валюту:", buildMenu());
            return;
        }

        if (data.startsWith("cur:")) {
            String code = data.substring(4);
            String response;

            if (code.equals("ALL")) {
                response = fetchAll(username);
            } else {
                response = fetchOne(code, username);
            }

            editMessage(chatId, msgId, response, buildBackMenu());
        }
    }

    private String fetchOne(String code, String username) {
        String uri = "/api/rates/" + code;
        long start = System.currentTimeMillis();
        int status = 0;
        String response;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Client", "telegram-bot");
            headers.set("X-User", username);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    trackerUrl + uri, HttpMethod.GET, entity, Map.class);

            status = resp.getStatusCode().value();
            Map body = resp.getBody();
            if (body == null || !"ok".equals(body.get("status"))) {
                response = "❌ Нет данных по " + code + ". Попробуй позже.";
            } else {
                String name = (String) body.get("name");
                Double rate = ((Number) body.get("rate")).doubleValue();
                String fetchedAt = (String) body.get("fetchedAt");
                response = String.format("💱 %s (%s)%nКурс: %.4f ₽%nОбновлено: %s",
                        name, code, rate, fetchedAt);
            }
        } catch (HttpStatusCodeException e) {
            status = e.getStatusCode().value();
            response = "❌ HTTP " + status + ": " + e.getResponseBodyAsString();
        } catch (Exception e) {
            status = 0;
            response = "❌ Ошибка: " + e.getMessage();
        }
        recordOutgoing(uri, code, status, username, start);
        return response;
    }

    private String fetchAll(String username) {
        String uri = "/api/rates";
        long start = System.currentTimeMillis();
        int status = 0;
        String response;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Client", "telegram-bot");
            headers.set("X-User", username);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    trackerUrl + uri, HttpMethod.GET, entity, Map.class);

            status = resp.getStatusCode().value();
            Map body = resp.getBody();
            if (body == null || body.isEmpty()) {
                response = "❌ Нет данных. Попробуй позже.";
            } else {
                StringBuilder sb = new StringBuilder("📊 Курсы валют (₽ за 1 ед.):\n\n");
                for (Object key : body.keySet()) {
                    sb.append(String.format("%s: %.4f%n", key, ((Number) body.get(key)).doubleValue()));
                }
                response = sb.toString();
            }
        } catch (HttpStatusCodeException e) {
            status = e.getStatusCode().value();
            response = "❌ HTTP " + status + ": " + e.getResponseBodyAsString();
        } catch (Exception e) {
            status = 0;
            response = "❌ Ошибка: " + e.getMessage();
        }
        recordOutgoing(uri, "ALL", status, username, start);
        return response;
    }

    private void recordOutgoing(String uri, String code, int status, String username, long startMs) {
        meterRegistry.counter("bot_gateway_requests_total",
                "uri", uri,
                "code", code,
                "status", String.valueOf(status),
                "user", username
        ).increment();

        meterRegistry.timer("bot_gateway_request_duration_seconds",
                "uri", uri
        ).record(System.currentTimeMillis() - startMs, TimeUnit.MILLISECONDS);
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ
    // ============================================================

    private void editMessage(Long chatId, Integer msgId, String text, InlineKeyboardMarkup markup) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId.toString());
        edit.setMessageId(msgId);
        edit.setText(text);
        edit.setReplyMarkup(markup);
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void answerCallback(String id) {
        AnswerCallbackQuery a = new AnswerCallbackQuery();
        a.setCallbackQueryId(id);
        try {
            execute(a);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(Long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
