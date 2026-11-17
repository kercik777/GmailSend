package com.gmail;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Простое приватное хранилище настроек (без дополнительной библиотеки шифрования).
 *
 * Приложение делается для себя, поэтому достаточно SharedPreferences в
 * приватной папке приложения: другие приложения доступа к ним не имеют,
 * а резервное копирование отключено в манифесте (allowBackup=false).
 *
 * Черновики и счётчики лежат в отдельном файле, чтобы «Очистить настройки»
 * не обнуляла статистику.
 */
public final class SettingsStore {

    private static final String SETTINGS_FILE = "aurora_mail_settings";
    private static final String DATA_FILE = "aurora_mail_data";

    private static final String KEY_EMAIL = "email";
    private static final String KEY_PASSWORD = "app_password";
    private static final String KEY_SENDER_NAME = "sender_name";
    private static final String KEY_DEFAULT_TO = "default_to";
    private static final String KEY_HOST = "smtp_host";
    private static final String KEY_PORT = "smtp_port";
    private static final String KEY_SSL = "smtp_ssl";

    private static final String KEY_SENT = "sent_count";
    private static final String KEY_TOTAL_FILES = "total_files";
    private static final String KEY_LAST_SENT = "last_sent";

    private static final String KEY_DRAFT_TO = "draft_to";
    private static final String KEY_DRAFT_SUBJECT = "draft_subject";
    private static final String KEY_DRAFT_BODY = "draft_body";

    public static final String DEFAULT_HOST = "smtp.gmail.com";
    public static final int DEFAULT_PORT = 587;
    public static final String DEFAULT_SENDER_NAME = "Aurora Mail";

    private final SharedPreferences prefs;
    private final SharedPreferences data;

    public SettingsStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE);
        data = app.getSharedPreferences(DATA_FILE, Context.MODE_PRIVATE);
    }

    public String getEmail() {
        return prefs.getString(KEY_EMAIL, "");
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public String getSenderName() {
        String name = prefs.getString(KEY_SENDER_NAME, "");
        return name.isEmpty() ? DEFAULT_SENDER_NAME : name;
    }

    public String getDefaultTo() {
        return prefs.getString(KEY_DEFAULT_TO, "");
    }

    public String getHost() {
        String host = prefs.getString(KEY_HOST, "");
        return host.isEmpty() ? DEFAULT_HOST : host;
    }

    public int getPort() {
        int port = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        return (port > 0 && port <= 65535) ? port : DEFAULT_PORT;
    }

    public boolean isSsl() {
        return prefs.getBoolean(KEY_SSL, false);
    }

    public boolean isConfigured() {
        return !getEmail().trim().isEmpty() && !getPassword().trim().isEmpty();
    }

    public void save(String email, String password, String senderName, String defaultTo,
                     String host, int port, boolean ssl) {
        prefs.edit()
                .putString(KEY_EMAIL, email.trim())
                .putString(KEY_PASSWORD, password.trim())
                .putString(KEY_SENDER_NAME, senderName.trim())
                .putString(KEY_DEFAULT_TO, defaultTo.trim())
                .putString(KEY_HOST, host.trim())
                .putInt(KEY_PORT, port)
                .putBoolean(KEY_SSL, ssl)
                .apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    // --- статистика ---

    public int getSentCount() {
        return data.getInt(KEY_SENT, 0);
    }

    public int getTotalFiles() {
        return data.getInt(KEY_TOTAL_FILES, 0);
    }

    public long getLastSentAt() {
        return data.getLong(KEY_LAST_SENT, 0L);
    }

    public void registerSent(int files) {
        data.edit()
                .putInt(KEY_SENT, getSentCount() + 1)
                .putInt(KEY_TOTAL_FILES, getTotalFiles() + Math.max(0, files))
                .putLong(KEY_LAST_SENT, System.currentTimeMillis())
                .apply();
    }

    // --- черновик ---

    public void saveDraft(String to, String subject, String body) {
        data.edit()
                .putString(KEY_DRAFT_TO, to)
                .putString(KEY_DRAFT_SUBJECT, subject)
                .putString(KEY_DRAFT_BODY, body)
                .apply();
    }

    public String getDraftTo() {
        return data.getString(KEY_DRAFT_TO, "");
    }

    public String getDraftSubject() {
        return data.getString(KEY_DRAFT_SUBJECT, "");
    }

    public String getDraftBody() {
        return data.getString(KEY_DRAFT_BODY, "");
    }
}
