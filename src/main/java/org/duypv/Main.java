package org.duypv;

import org.duypv.bot.CheckingTimeBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) {
    boolean running = true;

    while (running) {
      try {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new CheckingTimeBot());

        log.info("Bot started successfully!");
        running = false;

      } catch (TelegramApiException e) {
        log.error("Can't connect to Telegram: {}", e.getMessage());
        log.error("Reconnect after 60 seconds...");

        try {
          Thread.sleep(60_000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          log.info("Main thread interrupted, stopping application.");
          running = false;
        }
      }
    }
  }
}
