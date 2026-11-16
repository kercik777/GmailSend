package com.gmail;

import android.util.Log;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.activation.CommandMap;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.MailcapCommandMap;
import javax.mail.AuthenticationFailedException;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;

/**
 * Отправка писем по SMTP (STARTTLS 587 или SSL 465).
 *
 * Никаких учётных данных в коде: конфигурация приходит из настроек.
 * Вложения читаются потоково из DataSource, поэтому работают с content:// Uri
 * на Android 10+ без разрешений на хранилище.
 */
public final class GmailSender {

    private static final String TAG = "AuroraMail";
    private static final int TIMEOUT_MS = 20000;

    public enum ErrorKind { AUTH, NETWORK, MESSAGE, UNKNOWN }

    public interface Callback {
        void onSuccess();

        void onError(ErrorKind kind, String detail);
    }

    /** Параметры соединения. Пароль живёт только в памяти во время отправки. */
    public static final class Config {
        public final String email;
        public final String password;
        public final String displayName;
        public final String host;
        public final int port;
        public final boolean ssl;

        public Config(String email, String password, String displayName,
                      String host, int port, boolean ssl) {
            this.email = email == null ? "" : email.trim();
            this.password = password == null ? "" : password.trim();
            this.displayName = displayName == null || displayName.trim().isEmpty()
                    ? SettingsStore.DEFAULT_SENDER_NAME : displayName.trim();
            this.host = host == null || host.trim().isEmpty()
                    ? SettingsStore.DEFAULT_HOST : host.trim();
            this.port = (port > 0 && port <= 65535) ? port : SettingsStore.DEFAULT_PORT;
            this.ssl = ssl;
        }
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "aurora-mail-worker");
        thread.setDaemon(true);
        return thread;
    });

    static {
        ensureMailcap();
    }

    private GmailSender() {
    }

    public static void send(Config config, String to, String subject, String body,
                            List<DataSource> attachments, Callback callback) {
        if (config == null || config.email.isEmpty() || config.password.isEmpty()) {
            report(callback, new IllegalStateException("SMTP is not configured"));
            return;
        }
        POOL.execute(() -> {
            try {
                deliver(config, to, subject, body, attachments);
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception error) {
                Log.w(TAG, "Не удалось отправить письмо: " + error.getClass().getSimpleName());
                report(callback, error);
            }
        });
    }

    /** Проверка соединения и логина без отправки письма. */
    public static void verify(Config config, Callback callback) {
        if (config == null || config.email.isEmpty() || config.password.isEmpty()) {
            report(callback, new IllegalStateException("SMTP is not configured"));
            return;
        }
        POOL.execute(() -> {
            try {
                Session session = buildSession(config);
                Transport transport = session.getTransport("smtp");
                try {
                    transport.connect(config.host, config.port, config.email, config.password);
                } finally {
                    try {
                        transport.close();
                    } catch (Exception ignored) {
                    }
                }
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception error) {
                Log.w(TAG, "Проверка соединения не удалась: " + error.getClass().getSimpleName());
                report(callback, error);
            }
        });
    }

    private static void deliver(Config config, String to, String subject, String body,
                                List<DataSource> attachments) throws Exception {
        Session session = buildSession(config);

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.email, config.displayName, "UTF-8"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        message.setSubject(subject == null || subject.trim().isEmpty() ? "(без темы)" : subject, "UTF-8");
        message.setSentDate(new Date());
        message.setHeader("X-Mailer", "Aurora Mail for Android");

        String safeBody = body == null ? "" : body;

        // Текстовая и HTML-версия письма (альтернативный multipart).
        MimeBodyPart plainPart = new MimeBodyPart();
        plainPart.setText(safeBody, "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(buildHtml(subject, safeBody, config.displayName), "text/html; charset=UTF-8");

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(plainPart);
        alternative.addBodyPart(htmlPart);

        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart contentPart = new MimeBodyPart();
        contentPart.setContent(alternative);
        mixed.addBodyPart(contentPart);

        if (attachments != null) {
            for (DataSource source : attachments) {
                if (source == null) {
                    continue;
                }
                MimeBodyPart attachmentPart = new MimeBodyPart();
                attachmentPart.setDataHandler(new DataHandler(source));
                attachmentPart.setFileName(MimeUtility.encodeText(source.getName(), "UTF-8", null));
                mixed.addBodyPart(attachmentPart);
            }
        }

        message.setContent(mixed);
        message.saveChanges();

        Transport transport = session.getTransport("smtp");
        try {
            transport.connect(config.host, config.port, config.email, config.password);
            transport.sendMessage(message, message.getAllRecipients());
        } finally {
            try {
                transport.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static Session buildSession(Config config) {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.host);
        props.put("mail.smtp.port", String.valueOf(config.port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");

        if (config.ssl) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", String.valueOf(config.port));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.fallback", "false");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.email, config.password);
            }
        });
    }

    /** Красивое письмо в стиле приложения, которое увидит получатель. */
    private static String buildHtml(String subject, String body, String senderName) {
        String title = escapeHtml(subject == null || subject.trim().isEmpty() ? "Новое письмо" : subject);
        String content = escapeHtml(body).replace("\r\n", "\n").replace("\n", "<br>");
        if (content.isEmpty()) {
            content = "<span style='color:#8D99BA'>(без текста)</span>";
        }
        return "<div style=\"margin:0;padding:0;background:#0B1020;font-family:Arial,Helvetica,sans-serif\">"
                + "<div style=\"max-width:600px;margin:0 auto;padding:32px 16px\">"
                + "<div style=\"background:linear-gradient(135deg,#7C6BFF,#3DE8C5);border-radius:20px 20px 0 0;padding:30px 32px;color:#ffffff\">"
                + "<div style=\"font-size:10px;letter-spacing:4px;opacity:.85\">AURORA MAIL</div>"
                + "<h1 style=\"margin:14px 0 0;font-size:23px;font-weight:bold;line-height:1.3\">" + title + "</h1>"
                + "</div>"
                + "<div style=\"background:#151C30;border:1px solid #2A3454;border-top:0;border-radius:0 0 20px 20px;padding:30px 32px;color:#EDF1FF;font-size:14px;line-height:1.75\">"
                + content
                + "</div>"
                + "<div style=\"text-align:center;color:#6D7899;font-size:11px;padding:20px\">"
                + "Отправлено из <b style=\"color:#AC9DFF\">Aurora Mail</b> · " + escapeHtml(senderName)
                + "</div></div></div>";
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void ensureMailcap() {
        try {
            MailcapCommandMap mailcap = (MailcapCommandMap) CommandMap.getDefaultCommandMap();
            mailcap.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed");
            mailcap.addMailcap("multipart/mixed;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed");
            mailcap.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain");
            mailcap.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html");
            mailcap.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822");
            CommandMap.setDefaultCommandMap(mailcap);
        } catch (Throwable ignored) {
            // Реализация activation на некоторых устройствах отличается — не критично.
        }
    }

    private static void report(Callback callback, Exception error) {
        if (callback == null) {
            return;
        }
        String chain = describe(error);
        ErrorKind kind;
        if (error instanceof AuthenticationFailedException
                || chain.contains("authenticationfailedexception")
                || chain.contains("535 ")
                || chain.contains("username and password not accepted")
                || chain.contains("invalid credentials")) {
            kind = ErrorKind.AUTH;
        } else if (chain.contains("unknownhost")
                || chain.contains("could not connect")
                || chain.contains("connection timed out")
                || chain.contains("connect timed out")
                || chain.contains("connection refused")
                || chain.contains("network is unreachable")
                || chain.contains("socketexception")
                || chain.contains("sslhandshake")
                || chain.contains("socket timeout")) {
            kind = ErrorKind.NETWORK;
        } else if (error instanceof MessagingException) {
            kind = ErrorKind.MESSAGE;
        } else {
            kind = ErrorKind.UNKNOWN;
        }
        String detail = error.getMessage() == null ? error.toString() : error.getMessage();
        callback.onError(kind, detail);
    }

    private static String describe(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            builder.append(current.getClass().getName())
                    .append(' ')
                    .append(String.valueOf(current.getMessage()))
                    .append(' ');
            current = current.getCause();
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
