package org.duypv.bot;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
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

    // ZoneId cho Việt Nam
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

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
        log.info("Received message from {}: {}", chatId, msg);

        if (msg.startsWith("/ci")) {
            handleCheckIn(chatId, msg);
        } else if (msg.startsWith("/go")) {
            handleGetOut(chatId, msg);
        } else if (msg.startsWith("/gi")) {
            handleGetIn(chatId, msg);
        } else if (msg.startsWith("/rs")) {
            handleReset(chatId);
        } else if (msg.startsWith("/rp")) {
            handleReport(chatId);
        }
    }

    private void handleCheckIn(Long chatId, String msg) {
        try {
            LocalTime now = LocalTime.now(VN_ZONE).truncatedTo(ChronoUnit.MINUTES);
            LocalTime checkin;
            String[] parts = msg.split(" ");
            if (parts.length == 1) {
                checkin = now;
            } else {
                checkin = LocalTime.parse(parts[1]).truncatedTo(ChronoUnit.MINUTES);
            }

            LocalTime checkout = checkin.plusHours(9).plusMinutes(48);

            // Lưu vào trạng thái user
            UserState state = userStates.computeIfAbsent(chatId, k -> new UserState());
            state.lastCheckIn = checkin;
            state.expectedCheckOut = checkout;

            // Gộp message
            StringBuilder sb = new StringBuilder();
            sb.append("✅ Bạn đã check-in lúc ").append(checkin).append("\n");
            sb.append("⏰ Thời gian check-out dự kiến: ").append(checkout);
            sendText(chatId, sb.toString());

            // Nhắc đúng giờ check-out
            long delay = Duration.between(now, checkout).toMillis();
            if (delay > 0) {
                scheduleAndReplace(chatId, "CHECK_OUT_ALERT",
                        scheduler.schedule(() -> sendText(chatId, "🔔 Nhắc nhở: Đã đến giờ check-out (" + checkout + ")"),
                                delay, TimeUnit.MILLISECONDS));
            }

            // Nhắc 10 phút trước giờ check-out với báo cáo
            long delayBefore = Duration.between(now, checkout.minusMinutes(10)).toMillis();
            if (delayBefore > 0) {
                scheduleAndReplace(chatId, "CHECK_OUT_ALERT_BEFORE",
                        scheduler.schedule(() -> {
                            UserState st = userStates.get(chatId);
                            long totalMinutes = st != null ? st.totalOutDuration.toMinutes() : 0;

                            String report = "*📋 Báo cáo:*\n"
                                    + "  ✅ Bạn đã check-in lúc " + checkin + "\n"
                                    + "  ⏰ Thời gian check-out dự kiến: " + checkout + "\n"
                                    + "  📊 Tổng thời gian đã đi ra ngoài: " + totalMinutes + " phút\n"
                                    + "  🔢 Số lần đi ra ngoài quá 30 phút: "
                                    + (st != null ? st.over30Count : 0) + " lần";

                            sendText(chatId, report);
                        }, delayBefore, TimeUnit.MILLISECONDS));
            }
        } catch (Exception e) {
            sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /ci hoặc /ci HH:mm");
        }
    }

    private void handleGetOut(Long chatId, String msg) {
        try {
            LocalTime getOut;
            String[] parts = msg.split(" ");
            if (parts.length == 1) {
                // Không có HH:mm → lấy thời gian hiện tại
                getOut = LocalTime.now(VN_ZONE).truncatedTo(ChronoUnit.MINUTES);
            } else {
                // Có HH:mm → parse thời gian từ input
                getOut = LocalTime.parse(parts[1]).truncatedTo(ChronoUnit.MINUTES);
            }

            // Lưu lại thời điểm get-out
            UserState state = userStates.computeIfAbsent(chatId, k -> new UserState());
            state.lastGetOut = getOut;

            LocalTime getIn = getOut.plusMinutes(30);

            StringBuilder sb = new StringBuilder();
            sb.append("🚪 Bạn đã get-out lúc ").append(getOut).append("\n");
            sb.append("🔙 Thời gian get-in tối đa: ").append(getIn);

            sendText(chatId, sb.toString());

            ScheduledFuture<?> alertTask = scheduler.schedule(
                    () -> sendText(chatId, "🔔 Nhắc nhở: Chuẩn bị get-in trước " + getIn),
                    Duration.ofMinutes(15).toMillis(),
                    TimeUnit.MILLISECONDS);
            scheduleAndReplace(chatId, "GET_IN_ALERT", alertTask);
        } catch (Exception e) {
            sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /go hoặc /go HH:mm");
        }
    }

    private void handleGetIn(Long chatId, String msg) {
        try {
            LocalTime getIn;
            String[] parts = msg.split(" ");
            if (parts.length == 1) {
                // Không có HH:mm → lấy thời gian hiện tại
                getIn = LocalTime.now(VN_ZONE).truncatedTo(ChronoUnit.MINUTES);
            } else {
                // Có HH:mm → parse thời gian từ input
                getIn = LocalTime.parse(parts[1]).truncatedTo(ChronoUnit.MINUTES);
            }

            UserState state = userStates.computeIfAbsent(chatId, k -> new UserState());

            StringBuilder sb = new StringBuilder();
            sb.append("🔙 Bạn đã get-in lúc ").append(getIn).append("\n");

            if (state.lastGetOut != null) {
                Duration outDuration = Duration.between(state.lastGetOut, getIn);
                state.totalOutDuration = state.totalOutDuration.plus(outDuration);

                long minutesThisOut = outDuration.toMinutes();
                long totalMinutes = state.totalOutDuration.toMinutes();

                sb.append("📊 Thời gian đi ra ngoài lần này: ").append(minutesThisOut).append(" phút\n");
                sb.append("📊 Tổng thời gian đã đi ra ngoài: ").append(totalMinutes).append(" phút\n");

                // ⚠️ Cảnh báo nếu đi ra ngoài quá lâu
                if (minutesThisOut > MAX_SINGLE_OUT_DURATION_MINUTES) {
                    sb.append("⚠️ Cảnh báo: Bạn đã đi ra ngoài hơn 30 phút!\n");
                    state.over30Count++;
                }

                // ⚠️ Cảnh báo nếu tổng >= 1 giờ
                if (totalMinutes >= MAX_OUT_DURATION_MINUTES) {
                    sb.append("⚠️ Cảnh báo: Tổng thời gian đi ra ngoài đã vượt quá 1 giờ!\n");
                }

                // Reset lastGetOut để tránh tính lại
                state.lastGetOut = null;
            } else {
                sb.append("⚠️ Bạn chưa có lần get-out nào để tính thời gian.\n");
            }

            sendText(chatId, sb.toString());

            // 🗑️ Xóa job GET_IN_ALERT nếu còn tồn tại
            Map<String, ScheduledFuture<?>> tasks = userSchedulers.get(chatId);
            if (tasks != null) {
                ScheduledFuture<?> alertTask = tasks.remove("GET_IN_ALERT");
                if (alertTask != null && !alertTask.isDone()) {
                    alertTask.cancel(true);
                }
            }
        } catch (Exception e) {
            sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /gi hoặc /gi HH:mm");
        }
    }

    private void handleReset(Long chatId) {
        // Hủy tất cả job của user
        Map<String, ScheduledFuture<?>> tasks = userSchedulers.remove(chatId);
        if (tasks != null) {
            for (ScheduledFuture<?> task : tasks.values()) {
                if (task != null && !task.isDone()) {
                    task.cancel(true);
                }
            }
        }

        // Xóa trạng thái user
        userStates.remove(chatId);

        sendText(chatId, "🔄 Ứng dụng đã được reset về trạng thái ban đầu.");
    }

    private void handleReport(Long chatId) {
        UserState state = userStates.get(chatId);
        if (state == null || state.lastCheckIn == null) {
            sendText(chatId, "⚠️ Bạn chưa check-in nên chưa có báo cáo.");
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append("✅ Thời gian check-in: ").append(state.lastCheckIn).append("\n");
        if (state.expectedCheckOut != null) {
            report.append("⏰ Thời gian check-out dự kiến: ").append(state.expectedCheckOut).append("\n");
        }
        report.append("📊 Tổng thời gian đã đi ra ngoài: ")
                .append(state.totalOutDuration.toMinutes()).append(" phút");

        sendText(chatId, report.toString());
    }

    private void sendText(Long chatId, String text) {
        SendMessage message = new SendMessage(chatId.toString(), text);
        message.setParseMode("Markdown"); // hoặc "MarkdownV2"
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
        LocalTime lastCheckIn;
        LocalTime expectedCheckOut;
        int over30Count = 0; // số lần đi ra ngoài quá 30 phút
    }
}
