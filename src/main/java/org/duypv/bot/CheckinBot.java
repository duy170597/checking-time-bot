package org.duypv.bot;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
public class CheckinBot extends TelegramLongPollingBot {
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

  // Map: mỗi user có một map con, key = loại task ("checkin", "getout-alert", "getout-getin")
  private final ConcurrentHashMap<Long, Map<String, ScheduledFuture<?>>> userSchedulers = new ConcurrentHashMap<>();

  @Override
  public String getBotUsername() {
    return "duypv_1_bot";
  }

  @Override
  public String getBotToken() {
    return "8326903819:AAGBEknxLkZp_XdS8Z6H0AdD1ElFCoPX6nY"; // thay bằng token thật
  }

  @Override
  public void onUpdateReceived(Update update) {
    if (update.hasMessage() && update.getMessage().hasText()) {
      String msg = update.getMessage().getText();
      Long chatId = update.getMessage().getChatId();

      // ====== CASE /checkin ======
      if (msg.startsWith("/checkin")) {
        try {
          LocalTime checkin;
          String[] parts = msg.split(" ");
          if (parts.length == 1) {
            checkin = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);
            sendText(chatId, "✅ Bạn đã checkin lúc " + checkin + " (giờ hiện tại)");
          } else {
            String timeStr = parts[1];
            checkin = LocalTime.parse(timeStr);
            sendText(chatId, "✅ Bạn đã checkin lúc " + checkin + " (theo giờ nhập vào)");
          }

          LocalTime checkout = checkin.plusHours(9).plusMinutes(49);
          sendText(chatId, "⏰ Thời gian checkout dự kiến: " + checkout);

          long delay = java.time.Duration.between(LocalTime.now().truncatedTo(ChronoUnit.MINUTES), checkout).toMillis();
          if (delay > 0) {
            ScheduledFuture<?> newTask = scheduler.schedule(() ->
                    sendText(chatId, "🔔 Nhắc nhở: Đã đến giờ checkout (" + checkout + ")"),
                delay,
                TimeUnit.MILLISECONDS);
            addOrReplaceTask(chatId, "checkin", newTask);
          }
        } catch (Exception e) {
          sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /checkin HH:mm");
        }
      }

      // ====== CASE /getout ======
      else if (msg.startsWith("/getout")) {
        LocalTime getout = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);
        sendText(chatId, "🚪 Bạn đã Get out lúc " + getout);

        LocalTime getin = getout.plusMinutes(30);
        sendText(chatId, "🔙 Thời gian Get in dự kiến: " + getin);

        // alert sau 20 phút
        long delay20 = java.time.Duration.ofMinutes(20).toMillis();
        ScheduledFuture<?> alertTask = scheduler.schedule(() ->
                sendText(chatId, "🔔 Nhắc nhở: Chuẩn bị Get in sau 10 phút (dự kiến " + getin + ")"),
            delay20,
            TimeUnit.MILLISECONDS);
        addOrReplaceTask(chatId, "getout-alert", alertTask);

        // alert đúng lúc getin (sau 30 phút)
        long delay30 = java.time.Duration.ofMinutes(30).toMillis();
        ScheduledFuture<?> getinTask = scheduler.schedule(() ->
                sendText(chatId, "✅ Đã đến giờ Get in (" + getin + ")"),
            delay30,
            TimeUnit.MILLISECONDS);
        addOrReplaceTask(chatId, "getout-getin", getinTask);
      }
    }
  }

  private void sendText(Long chatId, String text) {
    SendMessage message = new SendMessage(chatId.toString(), text);
    try {
      execute(message);
    } catch (TelegramApiException e) {
      e.printStackTrace();
    }
  }

  // thêm hoặc thay thế task theo loại
  private void addOrReplaceTask(Long chatId, String type, ScheduledFuture<?> task) {
    userSchedulers.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>());
    Map<String, ScheduledFuture<?>> tasks = userSchedulers.get(chatId);

    ScheduledFuture<?> oldTask = tasks.get(type);
    if (oldTask != null && !oldTask.isDone()) {
      oldTask.cancel(true);
    }

    tasks.put(type, task);
  }
}

