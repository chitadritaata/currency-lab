package com.example.bot;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class MyBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(MyBot.class);
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";
    private static final String APP = "telegram-bot";

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

    @Override
    public void onUpdateReceived(Update update) {
        String traceId = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, traceId);

        try {
            if (update.hasCallbackQuery()) {
                CallbackQuery cb = update.getCallbackQuery();
                String username = username(cb.getFrom() != null ? cb.getFrom().getUserName() : null);

                log.info(">>> from=user:{} Received callback action={}",
                        username, cb.getData());
                meterRegistry.counter("bot_callbacks_incoming_total",
                        "user", username, "action", cb.getData()).increment();

                handleCallback(cb, username);
                return;
            }

            if (update.hasMessage() && update.getMessage().hasText()) {
                String text = update.getMessage().getText();
                Long chatId = update.getMessage().getChatId();
                String username = username(update.getMessage().getFrom() != null
                        ? update.getMessage().getFrom().getUserName() : null);

                log.info(">>> from=user:{} Received message text={}",
                        username, oneLine(text));
                meterRegistry.counter("bot_messages_incoming_total",
                        "user", username, "command", text.startsWith("/") ? text : "text").increment();

                if (text.equals("/start") || text.equals("/menu")) {
                    int status = sendMainMenu(chatId);
                    log.info("<<< to=user:{} Sent to user status={} text=menu",
                            username, status);
                } else {
                    int status = sendMessage(chatId, "Жми /start");
                    log.info("<<< to=user:{} Sent to user status={} text=Жми /start",
                            username, status);
                }
            }
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private int sendMainMenu(Long chatId) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText("💱 Выбери валюту:");
        msg.setReplyMarkup(buildMenu());
        return executeSend(msg);
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

    private void handleCallback(CallbackQuery cb, String username) {
        String data = cb.getData();
        Long chatId = cb.getMessage().getChatId();
        Integer msgId = cb.getMessage().getMessageId();

        answerCallback(cb.getId());

        String response;
        if (data.equals("menu:refresh")) {
            int status = editMessage(chatId, msgId, "💱 Выбери валюту:", buildMenu());
            log.info("<<< to=user:{} Sent to user status={} text=menu",
                    username, status);
            return;
        }

        if (data.startsWith("cur:")) {
            String code = data.substring(4);
            response = code.equals("ALL") ? fetchAll(username) : fetchOne(code, username);

            int status = editMessage(chatId, msgId, response, buildBackMenu());
            log.info("<<< to=user:{} Sent to user status={} text={}",
                    username, status, oneLine(response));
        }
    }

    private String fetchOne(String code, String username) {
        String uri = "/api/rates/" + code;
        long start = System.currentTimeMillis();
        int status = 0;
        String response = null;
        String bodyStr = "null";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Client", APP);
            headers.set("X-User", username);
            headers.set(TRACE_HEADER, MDC.get(MDC_KEY));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            log.info(">> to=currency-tracker Calling tracker uri={}", uri);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    trackerUrl + uri, HttpMethod.GET, entity, Map.class);

            status = resp.getStatusCode().value();
            Map body = resp.getBody();
            bodyStr = body == null ? "null" : body.toString();

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
            bodyStr = e.getResponseBodyAsString();
            response = "❌ HTTP " + status + ": " + e.getResponseBodyAsString();
        } catch (Exception e) {
            status = 0;
            bodyStr = e.getMessage();
            response = "❌ Ошибка: " + e.getMessage();
        }

        log.info("<< from=currency-tracker Tracker responded status={} body={}",
                status, oneLine(bodyStr));

        recordOutgoing(uri, code, status, username, start);
        return response;
    }

    private String fetchAll(String username) {
        String uri = "/api/rates";
        long start = System.currentTimeMillis();
        int status = 0;
        String response = null;
        String bodyStr = "null";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Client", APP);
            headers.set("X-User", username);
            headers.set(TRACE_HEADER, MDC.get(MDC_KEY));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            log.info(">> to=currency-tracker Calling tracker uri={}", uri);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    trackerUrl + uri, HttpMethod.GET, entity, Map.class);

            status = resp.getStatusCode().value();
            Map body = resp.getBody();
            bodyStr = body == null ? "null" : body.toString();

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
            bodyStr = e.getResponseBodyAsString();
            response = "❌ HTTP " + status + ": " + e.getResponseBodyAsString();
        } catch (Exception e) {
            status = 0;
            bodyStr = e.getMessage();
            response = "❌ Ошибка: " + e.getMessage();
        }

        log.info("<< from=currency-tracker Tracker responded status={} body={}",
                status, oneLine(bodyStr));

        recordOutgoing(uri, "ALL", status, username, start);
        return response;
    }

    private void recordOutgoing(String uri, String code, int status, String username, long startMs) {
        meterRegistry.counter("bot_gateway_requests_total",
                "uri", uri, "code", code, "status", String.valueOf(status), "user", username).increment();
        meterRegistry.timer("bot_gateway_request_duration_seconds", "uri", uri)
                .record(System.currentTimeMillis() - startMs, TimeUnit.MILLISECONDS);
    }

    private int editMessage(Long chatId, Integer msgId, String text, InlineKeyboardMarkup markup) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId.toString());
        edit.setMessageId(msgId);
        edit.setText(text);
        edit.setReplyMarkup(markup);
        return executeEdit(edit);
    }

    private void answerCallback(String id) {
        AnswerCallbackQuery a = new AnswerCallbackQuery();
        a.setCallbackQueryId(id);
        try {
            execute(a);
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback", e);
        }
    }

    private int sendMessage(Long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        return executeSend(msg);
    }

    private int executeSend(SendMessage msg) {
        try {
            execute(msg);
            return 200;
        } catch (TelegramApiRequestException e) {
            log.error("Telegram API error: code={} message={}", e.getErrorCode(), e.getMessage());
            return e.getErrorCode();
        } catch (TelegramApiException e) {
            log.error("Telegram error: {}", e.getMessage());
            return 0;
        }
    }

    private int executeEdit(EditMessageText msg) {
        try {
            execute(msg);
            return 200;
        } catch (TelegramApiRequestException e) {
            log.error("Telegram API error: code={} message={}", e.getErrorCode(), e.getMessage());
            return e.getErrorCode();
        } catch (TelegramApiException e) {
            log.error("Telegram error: {}", e.getMessage());
            return 0;
        }
    }
    private static String username(String raw) {
        return raw != null && !raw.isBlank() ? raw : "unknown";
    }

    private static String oneLine(String s) {
        if (s == null) return "null";
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}
