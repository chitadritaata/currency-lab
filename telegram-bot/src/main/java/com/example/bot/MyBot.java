package com.example.bot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
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

import java.util.*;

@Component
public class MyBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final String trackerUrl;
    private final RestTemplate restTemplate = new RestTemplate();

    public MyBot(@Value("${bot.token}") String botToken,
                 @Value("${bot.username}") String botUsername,
                 @Value("${tracker.url}") String trackerUrl) {
        super(botToken);
        this.botUsername = botUsername;
        this.trackerUrl = trackerUrl;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            Long chatId = update.getMessage().getChatId();
            if (text.equals("/start") || text.equals("/menu")) {
                sendMainMenu(chatId);
            } else {
                sendMessage(chatId, "Жми /start");
            }
        }
    }

    private void sendMainMenu(Long chatId) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText("💱 Выбери валюту:");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(btn("🇺🇸 Доллар США", "cur:USD"), btn("🇪🇺 Евро", "cur:EUR")));
        rows.add(List.of(btn("🇰🇷 Вона", "cur:KRW"), btn("🇨🇳 Юань", "cur:CNY")));
        rows.add(List.of(btn("🇯🇵 Йена", "cur:JPY")));
        rows.add(List.of(btn("📊 Все курсы", "cur:ALL")));

        markup.setKeyboard(rows);
        msg.setReplyMarkup(markup);

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

    private void handleCallback(CallbackQuery cb) {
        String data = cb.getData();
        Long chatId = cb.getMessage().getChatId();
        Integer msgId = cb.getMessage().getMessageId();

        answerCallback(cb.getId());

        if (data.equals("menu:refresh")) {
            editMessage(chatId, msgId, "💱 Выбери валюту:", buildMenu());
            return;
        }

        if (data.startsWith("cur:")) {
            String code = data.substring(4);
            String response;

            if (code.equals("ALL")) {
                response = fetchAll();
            } else {
                response = fetchOne(code);
            }

            editMessage(chatId, msgId, response, buildBackMenu());
        }
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

    private String fetchOne(String code) {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                    trackerUrl + "/api/rates/" + code, Map.class);
            Map body = resp.getBody();
            if (body == null || !"ok".equals(body.get("status"))) {
                return "❌ Нет данных по " + code + ". Попробуй позже.";
            }
            String name = (String) body.get("name");
            Double rate = ((Number) body.get("rate")).doubleValue();
            String fetchedAt = (String) body.get("fetchedAt");

            return String.format("""
                    💱 %s (%s)
                    Курс: %.4f ₽
                    Обновлено: %s
                    """, name, code, rate, fetchedAt);
        } catch (Exception e) {
            return "❌ Ошибка: " + e.getMessage();
        }
    }

    private String fetchAll() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                    trackerUrl + "/api/rates", Map.class);
            Map body = resp.getBody();
            if (body == null || body.isEmpty()) {
                return "❌ Нет данных. Попробуй позже.";
            }
            StringBuilder sb = new StringBuilder("📊 Курсы валют (₽ за 1 ед.):\n\n");
            for (Object key : body.keySet()) {
                Object val = body.get(key);
                sb.append(String.format("%s: %.4f\n", key, ((Number) val).doubleValue()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ Ошибка: " + e.getMessage();
        }
    }

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
