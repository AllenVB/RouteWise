package com.routewise.notification;

/**
 * Outgoing e-mail. Kept behind a port so the notification flow is complete and
 * tested before an SMTP dependency is chosen; today's implementation only logs.
 */
public interface MailPort {

    /** @return true if the message left; false if no mail channel is configured */
    boolean send(String to, String subject, String body);
}
