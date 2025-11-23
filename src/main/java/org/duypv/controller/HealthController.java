package org.duypv.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  private static final Logger log = LoggerFactory.getLogger(HealthController.class);

  @GetMapping("/")
  public String home() {
    log.info("Received request at home endpoint");
    return "Bot is running!";
  }

  @GetMapping("/health")
  public String health() {
    log.info("Received request at health endpoint");
    return "OK";
  }
}
