package com.routewise.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder e-mail channel: logs and reports "not sent", so no EMAIL row ever
 * claims a delivery that did not happen.
 */
@Component
public class LoggingMailPort implements MailPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailPort.class);

    @Override
    public boolean send(String to, String subject, String body) {
        log.info("e-posta kanali yapilandirilmadi; gonderilmedi: {} — {}", to, subject);
        return false;
    }
}
