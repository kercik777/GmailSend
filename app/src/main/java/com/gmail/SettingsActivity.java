package com.gmail;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Экран настроек: аккаунт, пароль приложения, SMTP и проверка соединения. */
public class SettingsActivity extends AppCompatActivity {

    private static final String APP_PASSWORDS_URL = "https://myaccount.google.com/apppasswords";

    private SettingsStore store;

    private FrameLayout root;
    private TextInputLayout tilEmail;
    private TextInputLayout tilPassword;
    private TextInputLayout tilName;
    private TextInputLayout tilDefaultTo;
    private TextInputLayout tilHost;
    private TextInputLayout tilPort;
    private TextInputEditText editEmail;
    private TextInputEditText editPassword;
    private TextInputEditText editName;
    private TextInputEditText editDefaultTo;
    private TextInputEditText editHost;
    private TextInputEditText editPort;
    private SwitchCompat switchSsl;
    private Button btnSave;
    private Button btnTest;
    private View progressOverlay;

    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Пароль приложения не должен попадать в скриншоты и список недавних задач.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);

        store = new SettingsStore(this);
        bindViews();
        load();
        setupListeners();
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        tilEmail = findViewById(R.id.tilEmail);
        tilPassword = findViewById(R.id.tilPassword);
        tilName = findViewById(R.id.tilName);
        tilDefaultTo = findViewById(R.id.tilDefaultTo);
        tilHost = findViewById(R.id.tilHost);
        tilPort = findViewById(R.id.tilPort);
        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        editName = findViewById(R.id.editName);
        editDefaultTo = findViewById(R.id.editDefaultTo);
        editHost = findViewById(R.id.editHost);
        editPort = findViewById(R.id.editPort);
        switchSsl = findViewById(R.id.switchSsl);
        btnSave = findViewById(R.id.btnSave);
        btnTest = findViewById(R.id.btnTest);
        progressOverlay = findViewById(R.id.progressOverlay);
    }

    private void load() {
        loading = true;
        editEmail.setText(store.getEmail());
        editPassword.setText(store.getPassword());
        editName.setText(store.getSenderName());
        editDefaultTo.setText(store.getDefaultTo());
        editHost.setText(store.getHost());
        switchSsl.setChecked(store.isSsl());
        editPort.setText(String.valueOf(store.getPort()));
        loading = false;
    }

    private void setupListeners() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        switchSsl.setOnCheckedChangeListener((button, checked) -> {
            if (loading) {
                return;
            }
            String port = text(editPort);
            if (checked && "587".equals(port)) {
                editPort.setText("465");
            } else if (!checked && "465".equals(port)) {
                editPort.setText("587");
            }
        });

        btnSave.setOnClickListener(v -> {
            if (commit()) {
                snack(R.string.settings_saved);
            }
        });

        btnTest.setOnClickListener(v -> {
            if (!commit()) {
                return;
            }
            setChecking(true);
            GmailSender.Config config = currentConfig();
            GmailSender.verify(config, new GmailSender.Callback() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        setChecking(false);
                        snack(R.string.connection_ok);
                    });
                }

                @Override
                public void onError(GmailSender.ErrorKind kind, String detail) {
                    runOnUiThread(() -> {
                        setChecking(false);
                        if (kind == GmailSender.ErrorKind.AUTH) {
                            snack(R.string.error_auth);
                        } else if (kind == GmailSender.ErrorKind.NETWORK) {
                            snack(R.string.error_connection);
                        } else {
                            snack(R.string.error_bad_settings);
                        }
                    });
                }
            });
        });

        findViewById(R.id.btnHelpLink).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APP_PASSWORDS_URL)));
            } catch (Exception ignored) {
                snack(R.string.error_no_browser);
            }
        });

        findViewById(R.id.btnClear).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.clear_confirm_title)
                        .setMessage(R.string.clear_confirm_message)
                        .setPositiveButton(R.string.clear_confirm_yes, (dialog, which) -> {
                            store.clear();
                            load();
                            snack(R.string.settings_cleared);
                        })
                        .setNegativeButton(R.string.clear_confirm_no, null)
                        .show());
    }

    /** Проверяет поля и сохраняет настройки. Возвращает false при ошибке. */
    private boolean commit() {
        clearErrors();

        String email = text(editEmail).trim();
        String password = text(editPassword).trim();
        String name = text(editName).trim();
        String defaultTo = text(editDefaultTo).trim();
        String host = text(editHost).trim();

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError(getString(R.string.error_settings_email));
            tilEmail.requestFocus();
            return false;
        }
        if (password.isEmpty()) {
            tilPassword.setError(getString(R.string.error_settings_password));
            tilPassword.requestFocus();
            return false;
        }
        if (!defaultTo.isEmpty() && !Patterns.EMAIL_ADDRESS.matcher(defaultTo).matches()) {
            tilDefaultTo.setError(getString(R.string.error_settings_email));
            tilDefaultTo.requestFocus();
            return false;
        }
        if (host.isEmpty()) {
            tilHost.setError(getString(R.string.error_settings_host));
            tilHost.requestFocus();
            return false;
        }

        int port;
        try {
            port = Integer.parseInt(text(editPort).trim());
        } catch (NumberFormatException ignored) {
            port = 0;
        }
        if (port < 1 || port > 65535) {
            tilPort.setError(getString(R.string.error_settings_port));
            tilPort.requestFocus();
            return false;
        }

        store.save(email, password, name, defaultTo, host, port, switchSsl.isChecked());
        return true;
    }

    private void clearErrors() {
        tilEmail.setError(null);
        tilPassword.setError(null);
        tilName.setError(null);
        tilDefaultTo.setError(null);
        tilHost.setError(null);
        tilPort.setError(null);
    }

    private GmailSender.Config currentConfig() {
        return new GmailSender.Config(
                store.getEmail(), store.getPassword(), store.getSenderName(),
                store.getHost(), store.getPort(), store.isSsl());
    }

    private void setChecking(boolean active) {
        progressOverlay.setVisibility(active ? View.VISIBLE : View.GONE);
        btnSave.setEnabled(!active);
        btnTest.setEnabled(!active);
    }

    private void snack(int messageRes) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Snackbar bar = Snackbar.make(root, messageRes, Snackbar.LENGTH_LONG);
        bar.setBackgroundTint(ContextCompat.getColor(this, R.color.snack_bg));
        bar.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        bar.show();
    }

    private static String text(TextView view) {
        return view.getText() == null ? "" : view.getText().toString();
    }
}
