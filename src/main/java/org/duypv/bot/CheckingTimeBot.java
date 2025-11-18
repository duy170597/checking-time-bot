package org.duypv.bot;

import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class CheckingTimeBot extends TelegramLongPollingBot {

  private final Logger log = LoggerFactory.getLogger(CheckingTimeBot.class);
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
  private final ConcurrentHashMap<Long, Map<String, ScheduledFuture<?>>> userSchedulers = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, UserState> userStates = new ConcurrentHashMap<>();

  private static final long MAX_OUT_DURATION_MINUTES = 60;
  private static final long MAX_SINGLE_OUT_DURATION_MINUTES = 30;

  public CheckingTimeBot() {
    super("8326903819:AAGBEknxLkZp_XdS8Z6H0AdD1ElFCoPX6nY");
  }

  @Override
  public String getBotUsername() {
    return "duypv_1_bot";
  }

  @Override
  public void onUpdateReceived(Update update) {
    if (!update.hasMessage() || !update.getMessage().hasText()) return;

    String msg = update.getMessage().getText();
    Long chatId = update.getMessage().getChatId();

    if (msg.startsWith("/checkin")) {
      handleCheckIn(chatId, msg);
    } else if (msg.startsWith("/getout")) {
      handleGetOut(chatId, msg);
    } else if (msg.startsWith("/getin")) {
      handleGetIn(chatId, msg);
    }
  }

  private void handleCheckIn(Long chatId, String msg) {
    try {
      LocalTime now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);
      LocalTime checkin;
      String[] parts = msg.split(" ");
      if (parts.length == 1) {
        checkin = now;
        sendText(chatId, "✅ Bạn đã check-in lúc " + checkin);
      } else {
        checkin = LocalTime.parse(parts[1]);
        sendText(chatId, "✅ Bạn đã check-in lúc " + checkin);
      }

      LocalTime checkout = checkin.plusHours(9).plusMinutes(48);
      sendText(chatId, "⏰ Thời gian check-out dự kiến: " + checkout);

      long delay = Duration.between(now, checkout).toMillis();
      if (delay > 0) {
        scheduleAndReplace(chatId, "CHECK_OUT_ALERT",
            scheduler.schedule(() -> sendText(chatId, "🔔 Nhắc nhở: Đã đến giờ check-out (" + checkout + ")"),
                delay, TimeUnit.MILLISECONDS));
      }
    } catch (Exception e) {
      sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /checkin hoặc /checkin HH:mm");
    }
  }

  private void handleGetOut(Long chatId, String msg) {
    try {
      LocalTime getOut;
      String[] parts = msg.split(" ");
      if (parts.length == 1) {
        // Không có HH:mm → lấy thời gian hiện tại
        getOut = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);
      } else {
        // Có HH:mm → parse thời gian từ input
        getOut = LocalTime.parse(parts[1]).truncatedTo(ChronoUnit.MINUTES);
      }
      sendText(chatId, "🚪 Bạn đã get-out lúc " + getOut);

      // Lưu lại thời điểm get-out
      UserState state = userStates.computeIfAbsent(chatId, k -> new UserState());
      state.lastGetOut = getOut;

      LocalTime getIn = getOut.plusMinutes(30);
      sendText(chatId, "🔙 Thời gian get-in tối đa: " + getIn);

      ScheduledFuture<?> alertTask = scheduler.schedule(
          () -> sendText(chatId, "🔔 Nhắc nhở: Chuẩn bị get-in trước 10 phút"),
          Duration.ofMinutes(20).toMillis(),
          TimeUnit.MILLISECONDS);
      scheduleAndReplace(chatId, "GET_IN_ALERT", alertTask);
    } catch (Exception e) {
      sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /getout hoặc /getout HH:mm");
    }
  }

  private void handleGetIn(Long chatId, String msg) {
    try {
      LocalTime getIn;
      String[] parts = msg.split(" ");
      if (parts.length == 1) {
        // Không có HH:mm → lấy thời gian hiện tại
        getIn = LocalTime.now().truncatedTo(ChronoUnit.MINUTES);
      } else {
        // Có HH:mm → parse thời gian từ input
        getIn = LocalTime.parse(parts[1]).truncatedTo(ChronoUnit.MINUTES);
      }
      sendText(chatId, "🔙 Bạn đã get-in lúc " + getIn);

      UserState state = userStates.computeIfAbsent(chatId, k -> new UserState());
      if (state.lastGetOut != null) {
        Duration outDuration = Duration.between(state.lastGetOut, getIn);
        state.totalOutDuration = state.totalOutDuration.plus(outDuration);

        long minutesThisOut = outDuration.toMinutes();
        long totalMinutes = state.totalOutDuration.toMinutes();

        sendText(chatId, "📊 Thời gian đi ra ngoài lần này: " + minutesThisOut + " phút");
        sendText(chatId, "📊 Tổng thời gian đã đi ra ngoài: " + totalMinutes + " phút");

        // ⚠️ Cảnh báo nếu đi ra ngoài quá lâu
        if (minutesThisOut > MAX_SINGLE_OUT_DURATION_MINUTES) {
          sendText(chatId, "⚠️ Cảnh báo: Bạn đã đi ra ngoài hơn 30 phút!");
        }

        // ⚠️ Cảnh báo nếu tổng >= 1 giờ
        if (totalMinutes >= MAX_OUT_DURATION_MINUTES) {
          sendText(chatId, "⚠️ Cảnh báo: Tổng thời gian đi ra ngoài đã vượt quá 1 giờ!");
        }

        // Reset lastGetOut để tránh tính lại
        state.lastGetOut = null;
      } else {
        sendText(chatId, "⚠️ Bạn chưa có lần get-out nào để tính thời gian.");
      }

      // 🗑️ Xóa job GET_IN_ALERT nếu còn tồn tại
      Map<String, ScheduledFuture<?>> tasks = userSchedulers.get(chatId);
      if (tasks != null) {
        ScheduledFuture<?> alertTask = tasks.remove("GET_IN_ALERT");
        if (alertTask != null && !alertTask.isDone()) {
          alertTask.cancel(true);
        }
      }
    } catch (Exception e) {
      sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /getin hoặc /getin HH:mm");
    }
  }

  private void sendText(Long chatId, String text) {
    SendMessage message = new SendMessage(chatId.toString(), text);
    try {
      execute(message);
    } catch (TelegramApiException e) {
      log.error("Failed to send message to {}: {}", chatId, e.getMessage());
    }
  }

  private void scheduleAndReplace(Long chatId, String type, ScheduledFuture<?> task) {
    Map<String, ScheduledFuture<?>> tasks = userSchedulers.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>());
    ScheduledFuture<?> old = tasks.put(type, task);
    if (old != null && !old.isDone()) old.cancel(true);
  }

  static class UserState {
    LocalTime lastGetOut;
    Duration totalOutDuration = Duration.ZERO;
  }
}
