package com.gmail;

import android.util.Log;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.activation.CommandMap;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.MailcapCommandMap;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;

/**
 * Отправка писем по SMTP через библиотеки из исходного проекта:
 * com.sun.mail:jakarta.mail:2.0.2 + com.sun.activation:jakarta.activation:2.0.1.
 *
 * Как и в оригинале, перебираем несколько SMTP-конфигураций: если порт 587
 * закрыт провайдером, уходим на 465/SSL и на smtp.googlemail.com.
 * Учётные данные приходят из настроек, в коде их больше нет.
 */
public final class GmailSender {

    private static final String TAG = "AuroraMail";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int IO_TIMEOUT_MS = 20000;

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

    /** Одна SMTP-конфигурация для попытки подключения. */
    private static final class Endpoint {
        final String host;
        final int port;
        final boolean ssl;

        Endpoint(String host, int port, boolean ssl) {
            this.host = host;
            this.port = port;
            this.ssl = ssl;
        }

        boolean same(Endpoint other) {
            return host.equalsIgnoreCase(other.host) && port == other.port && ssl == other.ssl;
        }

        @Override
        public String toString() {
            return host + ":" + port + (ssl ? "/SSL" : "/STARTTLS");
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
            report(callback, new IllegalStateException("SMTP is not configured"), "");
            return;
        }
        POOL.execute(() -> {
            List<Endpoint> endpoints = endpoints(config);
            try {
                deliver(config, endpoints, to, subject, body, attachments);
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception error) {
                Log.w(TAG, "Не удалось отправить письмо: " + error.getClass().getSimpleName()
                        + " — " + error.getMessage());
                report(callback, error, tried(endpoints));
            }
        });
    }

    /** Проверка соединения и логина без отправки письма. */
    public static void verify(Config config, Callback callback) {
        if (config == null || config.email.isEmpty() || config.password.isEmpty()) {
            report(callback, new IllegalStateException("SMTP is not configured"), "");
            return;
        }
        POOL.execute(() -> {
            List<Endpoint> endpoints = endpoints(config);
            try {
                connectAny(config, endpoints);
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception error) {
                Log.w(TAG, "Проверка соединения не удалась: " + error.getClass().getSimpleName()
                        + " — " + error.getMessage());
                report(callback, error, tried(endpoints));
            }
        });
    }

    // --- перебор конфигураций (как в исходном проекте) ---

    private static List<Endpoint> endpoints(Config config) {
        List<Endpoint> result = new ArrayList<>();
        add(result, new Endpoint(config.host, config.port, config.ssl));
        // Тот же хост, но другой режим — на случай неверно выбранного порта в настройках.
        if (!"smtp.gmail.com".equalsIgnoreCase(config.host)) {
            add(result, new Endpoint(config.host, config.ssl ? 587 : 465, !config.ssl));
        }
        add(result, new Endpoint("smtp.gmail.com", 587, false));
        add(result, new Endpoint("smtp.gmail.com", 465, true));
        add(result, new Endpoint("smtp.googlemail.com", 587, false));
        add(result, new Endpoint("smtp.googlemail.com", 465, true));
        return result;
    }

    private static void add(List<Endpoint> list, Endpoint endpoint) {
        for (Endpoint existing : list) {
            if (existing.same(endpoint)) {
                return;
            }
        }
        list.add(endpoint);
    }

    private static String tried(List<Endpoint> endpoints) {
        StringBuilder builder = new StringBuilder("Проверены: ");
        for (int i = 0; i < endpoints.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(endpoints.get(i));
        }
        return builder.toString();
    }

    private static void deliver(Config config, List<Endpoint> endpoints,
                                String to, String subject, String body,
                                List<DataSource> attachments) throws Exception {
        Exception last = null;
        for (Endpoint endpoint : endpoints) {
            try {
                sendVia(config, endpoint, to, subject, body, attachments);
                Log.i(TAG, "Письмо отправлено через " + endpoint);
                return;
            } catch (AuthenticationFailedException auth) {
                // Неверный логин — перебирать остальные серверы бессмысленно.
                throw auth;
            } catch (Exception error) {
                last = error;
                Log.w(TAG, endpoint + " → " + error.getClass().getSimpleName()
                        + ": " + error.getMessage());
            }
        }
        throw new MessagingException(
                "Ни один SMTP-сервер не ответил. " + tried(endpoints),
                last == null ? new Exception("Нет доступных конфигураций") : last);
    }

    private static void connectAny(Config config, List<Endpoint> endpoints) throws Exception {
        Exception last = null;
        for (Endpoint endpoint : endpoints) {
            Transport transport = null;
            try {
                Session session = buildSession(config, endpoint);
                transport = session.getTransport("smtp");
                transport.connect(endpoint.host, endpoint.port, config.email, config.password);
                Log.i(TAG, "Соединение установлено: " + endpoint);
                return;
            } catch (AuthenticationFailedException auth) {
                throw auth;
            } catch (Exception error) {
                last = error;
                Log.w(TAG, endpoint + " → " + error.getClass().getSimpleName()
                        + ": " + error.getMessage());
            } finally {
                if (transport != null) {
                    try {
                        transport.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw new MessagingException(
                "Ни один SMTP-сервер не ответил. " + tried(endpoints),
                last == null ? new Exception("Нет доступных конфигураций") : last);
    }

    private static void sendVia(Config config, Endpoint endpoint,
                                String to, String subject, String body,
                                List<DataSource> attachments) throws Exception {
        Transport transport = null;
        try {
            Session session = buildSession(config, endpoint);
            MimeMessage message = buildMessage(session, config, to, subject, body, attachments);
            transport = session.getTransport("smtp");
            transport.connect(endpoint.host, endpoint.port, config.email, config.password);
            transport.sendMessage(message, message.getAllRecipients());
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static MimeMessage buildMessage(Session session, Config config,
                                            String to, String subject, String body,
                                            List<DataSource> attachments) throws Exception {
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
        return message;
    }

    private static Session buildSession(Config config, Endpoint endpoint) {
        Properties props = new Properties();
        props.put("mail.smtp.host", endpoint.host);
        props.put("mail.smtp.port", String.valueOf(endpoint.port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(IO_TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(IO_TIMEOUT_MS));

        if (endpoint.ssl) {
            // Порт 465: SSL сразу, как во второй конфигурации исходного проекта.
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", String.valueOf(endpoint.port));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.fallback", "false");
            props.put("mail.smtp.ssl.protocols", "TLSv1.2");
        } else {
            // Порт 587: STARTTLS.
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.ssl.protocols", "TLSv1.2");
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

    private static void report(Callback callback, Exception error, String tried) {
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
        } else if (chain.contains("mailconnectexception")
                || chain.contains("unknownhost")
                || chain.contains("could not connect")
                || chain.contains("connection timed out")
                || chain.contains("connect timed out")
                || chain.contains("connection refused")
                || chain.contains("network is unreachable")
                || chain.contains("socketexception")
                || chain.contains("sslhandshake")
                || chain.contains("ни один smtp-сервер не ответил")) {
            kind = ErrorKind.NETWORK;
        } else if (error instanceof MessagingException) {
            kind = ErrorKind.MESSAGE;
        } else {
            kind = ErrorKind.UNKNOWN;
        }
        String detail = error.getMessage() == null ? error.toString() : error.getMessage();
        if (tried != null && !tried.isEmpty()) {
            detail = detail + "\n\n" + tried;
        }
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
