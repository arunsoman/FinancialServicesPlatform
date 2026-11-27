package com.fsp.core.notify.channel;

import com.fsp.core.config.FspProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Properties;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;

/**
 * SMTP transport adapter (V29; docs/architecture/12-notifications-and-push.md §12.3).
 * Credential-gated: {@code fsp.email.enabled} + SMTP coordinates via env
 * (FSP_EMAIL_*). Sends the generic pointer copy + the portal link — the link navigates
 * to an authorised route (the portal re-authenticates); the mail itself grants nothing
 * and never asks for credentials (anti-phishing framing rule, §12.5.6).
 * Outcomes: 250-accepted → Delivered; invalid mailbox/domain → definitive Unreachable
 * (the dispatcher flags the channel pref dead); other SMTP failures → transient.
 * Runs OUTSIDE any DB transaction (AGENTS §2d); logs never carry addresses or payload
 * text — SMTP codes and reason classes only (backend/AGENTS.md §2c).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fsp.email", name = "enabled", havingValue = "true")
public class EmailPublisher implements OutboundChannel {

    private final Session session;
    private final String from;
    private final String portalBaseUrl;

    public EmailPublisher(FspProperties props) {
        var cfg = props.getEmail();
        if (cfg.getHost().isBlank()) {
            throw new IllegalStateException("fsp.email is enabled but fsp.email.host is empty");
        }
        var p = new Properties();
        p.put("mail.smtp.host", cfg.getHost());
        p.put("mail.smtp.port", String.valueOf(cfg.getPort()));
        p.put("mail.smtp.auth", String.valueOf(!cfg.getUsername().isBlank()));
        p.put("mail.smtp.starttls.enable", String.valueOf(cfg.isStartTls()));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        this.session = Session.getInstance(p, cfg.getUsername().isBlank() ? null : new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(cfg.getUsername(), cfg.getPassword());
            }
        });
        this.from = cfg.getFrom();
        this.portalBaseUrl = cfg.getPortalBaseUrl();
    }

    @Override
    public String channel() {
        return ChannelWire.EMAIL;
    }

    @Override
    public String deliveryTag() {
        return "SMTP";
    }

    @Override
    public ChannelWire.SendOutcome send(ChannelWire.Target target, ChannelWire.Pointer pointer) {
        if (!ChannelAddresses.isPlausibleEmail(target.contact())) {
            return new ChannelWire.SendOutcome.Skipped("no_plausible_address");
        }
        try {
            var msg = new jakarta.mail.internet.MimeMessage(session);
            msg.setFrom(new InternetAddress(from, true));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(target.contact(), true));
            msg.setSubject(pointer.title(), "UTF-8");
            msg.setText(text(pointer), "UTF-8");
            msg.saveChanges();
            try (Transport t = session.getTransport("smtp")) {
                // a session with an Authenticator supplies the credentials itself;
                // without auth (relay mode) this is a plain unauthenticated connect
                t.connect();
                t.sendMessage(msg, msg.getAllRecipients());
            }
            log.info("email_dispatched person={} result=accepted", target.personId());
            return new ChannelWire.SendOutcome.Delivered();
        } catch (SendFailedException e) {
            // invalid mailbox/domain is definitive (5.1.x style validity failures surfaced here)
            boolean definitive = e.getInvalidAddresses() != null && e.getInvalidAddresses().length > 0
                || e.getMessage() != null && e.getMessage().contains("Invalid Addresses");
            log.warn("email_unreachable person={} definitive={} reason={}",
                target.personId(), definitive, e.getClass().getSimpleName());
            return new ChannelWire.SendOutcome.Unreachable(definitive, "smtp_rejected");
        } catch (AddressException e) {
            log.warn("email_unreachable person={} definitive=true reason=address_invalid", target.personId());
            return new ChannelWire.SendOutcome.Unreachable(true, "address_invalid");
        } catch (MessagingException e) {
            // transient by default — providers hiccup, TLS/auth blips resolve (bounded retry)
            log.warn("email_unreachable person={} definitive=false reason={}",
                target.personId(), e.getClass().getSimpleName());
            return new ChannelWire.SendOutcome.Unreachable(false, "smtp_transient");
        }
    }

    private String text(ChannelWire.Pointer pointer) {
        // auth-plane pointers (V30) carry their own inline copy and no link; the reset/mail
        // link is embedded in the body by the IdP. Normal F-12 pointers keep the portal link.
        String linkPart = pointer.link() == null
            ? ""
            : "\n\nOpen the portal for the details: " + portalBaseUrl + pointer.link();
        return pointer.title() + "\n\n" + pointer.body() + linkPart + "\n\n"
            + "This is an automated notice from your FSP operations portal. FSP never asks "
            + "for passwords or codes by email or phone.";
    }
}
