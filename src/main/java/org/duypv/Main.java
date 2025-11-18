package org.duypv;

import lombok.extern.slf4j.Slf4j;
import org.duypv.bot.CheckinBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Slf4j
public class Main {
  public static void main(String[] args) {
    try {
      // Khởi tạo API
      TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);

      // Đăng ký bot
      botsApi.registerBot(new CheckinBot());

      System.out.println("✅ Bot đã khởi động thành công!");
    } catch (TelegramApiException e) {
      e.printStackTrace();
    }
  }
}