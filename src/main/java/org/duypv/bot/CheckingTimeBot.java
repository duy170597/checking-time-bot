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
        } else if (msg.startsWith("/co")) {
            handleCheckOut(chatId, msg);
        } else if (msg.startsWith("/go")) {
            handleGetOut(chatId, msg);
        } else if (msg.startsWith("/gi")) {
            handleGetIn(chatId, msg);
        } else if (msg.startsWith("/rs")) {
            handleReset(chatId, true);
        } else if (msg.startsWith("/rp")) {
            handleReport(chatId);
        } else if (msg.startsWith("/lo")) {
            handleLunchOut(chatId, msg);
        } else if (msg.startsWith("/li")) {
            handleLunchIn(chatId, msg);
        } else if (msg.startsWith("/help")) {
            handleHelp(chatId);
        } else if (msg.startsWith("/")) {
            sendText(chatId, "⚠️ Cú pháp không hợp lệ. Vui lòng nhập /help để xem hướng dẫn sử dụng.");
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

            // Nhắc 10 phút trước giờ check-out (chỉ thông báo đơn giản)
            long delayBefore = Duration.between(now, checkout.minusMinutes(10)).toMillis();
            if (delayBefore > 0) {
                scheduleAndReplace(chatId, "CHECK_OUT_ALERT_BEFORE",
                        scheduler.schedule(() -> sendText(chatId,
                                        "🔔 Nhắc nhở: Gần đến giờ check-out (" + checkout + ")"),
                                delayBefore, TimeUnit.MILLISECONDS));
            }
        } catch (Exception e) {
            sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /ci hoặc /ci HH:mm");
        }
    }

    private void handleCheckOut(Long chatId, String msg) {
        try {
            UserState st = userStates.get(chatId);
            if (st == null || st.lastCheckIn == null) {
                sendText(chatId, "⚠️ Bạn chưa check-in nên chưa có báo cáo.");
                return;
            }

            LocalTime checkoutActual;
            String[] parts = msg.split(" ");
            if (parts.length == 1) {
                checkoutActual = LocalTime.now(VN_ZONE).truncatedTo(ChronoUnit.MINUTES);
            } else {
                checkoutActual = LocalTime.parse(parts[1]).truncatedTo(ChronoUnit.MINUTES);
            }

            long totalMinutes = st.totalOutDuration.toMinutes();

            // Tính thời gian làm việc ban đầu
            Duration workingDuration = Duration.between(st.lastCheckIn, checkoutActual);

            // Trừ đi thời gian nghỉ trưa (12:00 - 13:00)
            LocalTime lunchStart = LocalTime.of(12, 0);
            LocalTime lunchEnd = LocalTime.of(13, 0);

            // Nếu khoảng làm việc có giao với khoảng nghỉ trưa
            if (checkoutActual.isAfter(lunchStart) && st.lastCheckIn.isBefore(lunchEnd)) {
                LocalTime overlapStart = st.lastCheckIn.isAfter(lunchStart) ? st.lastCheckIn : lunchStart;
                LocalTime overlapEnd = checkoutActual.isBefore(lunchEnd) ? checkoutActual : lunchEnd;
                if (overlapEnd.isAfter(overlapStart)) {
                    Duration lunchBreak = Duration.between(overlapStart, overlapEnd);
                    workingDuration = workingDuration.minus(lunchBreak);
                }
            }

            long hours = workingDuration.toHours();
            long minutes = workingDuration.toMinutes() % 60;

            String report = "*📋 Báo cáo:*\n"
                    + "  🟢 Bạn đã check-in lúc " + st.lastCheckIn + "\n"
                    + "  🔴 Bạn đã check-out lúc: " + checkoutActual + "\n"
                    + "  ⏳ Tổng thời gian làm việc: " + hours + " giờ " + minutes + " phút\n"
                    + "  📊 Tổng thời gian đã đi ra ngoài: " + totalMinutes + " phút\n"
                    + "  🔢 Số lần đi ra ngoài quá 30 phút: " + st.over30Count + " lần";

            sendText(chatId, report);

            // Reset trạng thái user sau khi check-out
            handleReset(chatId, false);

        } catch (Exception e) {
            sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /co hoặc /co HH:mm");
        }
    }

    private void handleGetOut(Long chatId, String msg) {
        try {
            LocalTime getOut;
            String[] parts = msg.split(" ");
            if (parts.length == 1) {
                getOut = LocalTime.now(VN_ZONE).truncatedTo(ChronoUnit.MINUTES);
            } else {
                getOut = LocalTime.parse(parts[1]).truncatedTo(ChronoUnit.MINUTES);
            }
            UserState state = userStates.computeIfAbsent(chatId, k -> new UserState());
            state.lastGetOut = getOut;
            long remaining = MAX_OUT_DURATION_MINUTES - state.totalOutDuration.toMinutes();
            long addMinutes = Math.min(MAX_SINGLE_OUT_DURATION_MINUTES, Math.max(0, remaining));
            LocalTime getIn = getOut.plusMinutes(addMinutes);
            StringBuilder sb = new StringBuilder();
            sb.append("🚪 Bạn đã get-out lúc ").append(getOut).append("\n");
            sb.append("🔙 Thời gian get-in tối đa: ").append(getIn).append("\n");
            if (remaining <= 0) {
                sb.append("⚠️ Cảnh báo: Bạn không nên ra ngoài vì đã vượt quá 1 giờ cho phép!\n");
            }
            sendText(chatId, sb.toString());
            ScheduledFuture<?> alertTask = scheduler.schedule(() -> sendText(chatId, "🔔 Nhắc nhở: Chuẩn bị get-in trước " + getIn), Duration.ofMinutes(Math.min(15, addMinutes)).toMillis(), TimeUnit.MILLISECONDS);
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

    private void handleReset(Long chatId, boolean isSendText) {
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

        if (isSendText) {
            sendText(chatId, "🔄 Ứng dụng đã được reset về trạng thái ban đầu.");
        }
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
                .append(state.totalOutDuration.toMinutes()).append(" phút").append("\n");
        report.append("🔢 Số lần đi ra ngoài quá 30 phút: ")
                .append(state.over30Count).append(" lần");

        sendText(chatId, report.toString());
    }

    private void handleLunchOut(Long chatId, String msg) {
        try {
            LocalTime lunchOut;
            String[] parts = msg.split(" ");
            if (parts.length == 1) {
                lunchOut = LocalTime.now(VN_ZONE).truncatedTo(ChronoUnit.MINUTES);
            } else {
                lunchOut = LocalTime.parse(parts[1]).truncatedTo(ChronoUnit.MINUTES);
            }

            UserState state = userStates.computeIfAbsent(chatId, k -> new UserState());

            LocalTime minLunchStart = LocalTime.of(11, 30);
            LocalTime violationThreshold = LocalTime.of(11, 0);

            if (lunchOut.isBefore(minLunchStart)) {
                // Nếu nhập trước 11:30 thì tính từ 11:30
                Duration extra = Duration.between(lunchOut, minLunchStart);
                state.totalOutDuration = state.totalOutDuration.plus(extra);
                lunchOut = minLunchStart;

                // Nếu nhập trước 11:00 thì cộng thêm 1 lần vi phạm
                if (lunchOut.isBefore(violationThreshold.plusMinutes(30))) {
                    state.over30Count++;
                }
            }

            state.lastLunchOut = lunchOut;
            state.isLunching = true;

            // Tính giờ tối đa phải quay về: lunchOut + 1h30
            LocalTime maxLunchIn = lunchOut.plusHours(1).plusMinutes(30);

            StringBuilder sb = new StringBuilder();
            sb.append("🍽️ Bạn đã bắt đầu ăn trưa lúc ").append(lunchOut).append("\n");
            sb.append("⏰ Thời gian tối đa phải quay về sau khi ăn trưa: ").append(maxLunchIn);

            sendText(chatId, sb.toString());
        } catch (Exception e) {
            sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /lo hoặc /lo HH:mm");
        }
    }


    private void handleLunchIn(Long chatId, String msg) {
        try {
            LocalTime lunchIn;
            String[] parts = msg.split(" ");
            if (parts.length == 1) {
                lunchIn = LocalTime.now(VN_ZONE).truncatedTo(ChronoUnit.MINUTES);
            } else {
                lunchIn = LocalTime.parse(parts[1]).truncatedTo(ChronoUnit.MINUTES);
            }

            UserState state = userStates.computeIfAbsent(chatId, k -> new UserState());

            if (state.lastLunchOut == null || !state.isLunching) {
                sendText(chatId, "⚠️ Bạn chưa bắt đầu ăn trưa bằng /lo.");
                return;
            }

            Duration lunchDuration = Duration.between(state.lastLunchOut, lunchIn);
            long minutesLunch = lunchDuration.toMinutes();

            StringBuilder sb = new StringBuilder();
            sb.append("🍽️ Bạn đã kết thúc ăn trưa lúc ").append(lunchIn).append("\n");
            sb.append("⏳ Thời gian ăn trưa: ").append(minutesLunch).append(" phút\n");

            // Nếu ăn trưa > 60 phút → phần vượt quá cộng vào totalOutDuration
            if (minutesLunch > 60) {
                long exceed = minutesLunch - 60;
                state.totalOutDuration = state.totalOutDuration.plusMinutes(exceed);
                sb.append("⚠️ Bạn đã ăn trưa vượt quá 1 giờ, cộng thêm ").append(exceed).append(" phút vào tổng thời gian ra ngoài.\n");
            }

            // Quy tắc giống /go, /gi
            if (minutesLunch > (60 + MAX_SINGLE_OUT_DURATION_MINUTES)) {
                sb.append("⚠️ Cảnh báo: Thời gian ăn trưa vượt quá 1h30!\n");
                state.over30Count++;
            }

            sb.append("📊 Tổng thời gian đã đi ra ngoài: ").append(state.totalOutDuration.toMinutes()).append(" phút\n");

            state.lastLunchOut = null;
            state.isLunching = false;

            sendText(chatId, sb.toString());
        } catch (Exception e) {
            sendText(chatId, "❌ Cú pháp không hợp lệ. Vui lòng nhập: /li hoặc /li HH:mm");
        }
    }

    private void handleHelp(Long chatId) {
        StringBuilder sb = new StringBuilder();
        sb.append("*📖 Hướng dẫn sử dụng CheckingTimeBot:*\n\n");
        sb.append("✅ /ci [HH:mm] - Check-in (mặc định là giờ hiện tại nếu không nhập HH:mm)\n");
        sb.append("✅ /co [HH:mm] - Check-out và nhận báo cáo\n");
        sb.append("✅ /go [HH:mm] - Bắt đầu ra ngoài (tối đa 30 phút, tính theo quy tắc)\n");
        sb.append("✅ /gi [HH:mm] - Quay lại sau khi ra ngoài, cập nhật tổng thời gian\n");
        sb.append("✅ /lo [HH:mm] - Bắt đầu đi ăn trưa (từ 11:30 trở đi, tối đa 1h30)\n");
        sb.append("✅ /li [HH:mm] - Kết thúc ăn trưa, tính thời gian vượt quá nếu có\n");
        sb.append("✅ /rp - Xem báo cáo nhanh (check-in, check-out dự kiến, tổng thời gian ra ngoài)\n");
        sb.append("✅ /rs - Reset toàn bộ trạng thái\n");
        sb.append("✅ /help - Hiển thị bảng hướng dẫn này\n\n");
        sb.append("⚠️ Lưu ý:\n");
        sb.append("- Thời gian đi ra ngoài tối đa cho phép: 1 giờ (ăn trưa) + 30 phút cho mỗi lần.\n");
        sb.append("- Nếu vượt quá giới hạn, thời gian dư sẽ cộng vào tổng thời gian ra ngoài.\n");
        sb.append("- Nếu một lần ra ngoài > 30 phút, sẽ tăng số lần cảnh báo.\n");
        sendText(chatId, sb.toString());
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
        LocalTime lastLunchOut;
        boolean isLunching = false;
    }
}
