package com.gmail;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.activation.DataSource;

/** Главный экран: создание письма, вложения и отправка. */
public class MainActivity extends AppCompatActivity {

    private static final String[] ANY_FILE = {"*/*"};

    private SettingsStore store;

    private FrameLayout root;
    private View statusCard;
    private View statusDot;
    private TextView statusTitle;
    private TextView statusSubtitle;
    private TextView statSent;
    private TextView statFiles;
    private TextInputLayout tilTo;
    private TextInputLayout tilSubject;
    private TextInputEditText editTo;
    private TextInputEditText editSubject;
    private TextInputEditText editBody;
    private TextView attachmentsTitle;
    private TextView attachmentsCount;
    private LinearLayout attachmentsContainer;
    private Button btnSend;
    private View progressOverlay;
    private View successOverlay;
    private View successCard;
    private View successRing;
    private TextView successDetail;
    private ConfettiView confettiView;

    private final List<Attachment> attachments = new ArrayList<>();

    private ActivityResultLauncher<String[]> pickOne;
    private ActivityResultLauncher<String[]> pickMany;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideSuccessRunnable = this::hideSuccess;

    private ValueAnimator pulseAnimator;
    private boolean sending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new SettingsStore(this);
        bindViews();
        registerPickers();
        setupListeners();
        startPulse();

        if (savedInstanceState == null) {
            restoreDraft();
        }
        renderAttachments();
        refreshStatus();
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        statusCard = findViewById(R.id.statusCard);
        statusDot = findViewById(R.id.statusDot);
        statusTitle = findViewById(R.id.statusTitle);
        statusSubtitle = findViewById(R.id.statusSubtitle);
        statSent = findViewById(R.id.statSent);
        statFiles = findViewById(R.id.statFiles);
        tilTo = findViewById(R.id.tilTo);
        tilSubject = findViewById(R.id.tilSubject);
        editTo = findViewById(R.id.editTo);
        editSubject = findViewById(R.id.editSubject);
        editBody = findViewById(R.id.editBody);
        attachmentsTitle = findViewById(R.id.attachmentsTitle);
        attachmentsCount = findViewById(R.id.attachmentsCount);
        attachmentsContainer = findViewById(R.id.attachmentsContainer);
        btnSend = findViewById(R.id.btnSend);
        progressOverlay = findViewById(R.id.progressOverlay);
        successOverlay = findViewById(R.id.successOverlay);
        successCard = findViewById(R.id.successCard);
        successRing = findViewById(R.id.successRing);
        successDetail = findViewById(R.id.successDetail);
        confettiView = findViewById(R.id.confettiView);
    }

    private void registerPickers() {
        pickOne = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        addAttachment(uri);
                    }
                });

        pickMany = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null) {
                        for (Uri uri : uris) {
                            addAttachment(uri);
                        }
                    }
                });
    }

    private void setupListeners() {
        View.OnClickListener openSettings = v -> startActivity(new Intent(this, SettingsActivity.class));
        findViewById(R.id.btnSettings).setOnClickListener(openSettings);
        statusCard.setOnClickListener(openSettings);

        findViewById(R.id.btnAddOne).setOnClickListener(v -> pickOne.launch(ANY_FILE));
        findViewById(R.id.btnAddMany).setOnClickListener(v -> pickMany.launch(ANY_FILE));
        btnSend.setOnClickListener(v -> attemptSend());

        successOverlay.setOnClickListener(v -> hideSuccess());
        findViewById(R.id.btnSuccessClose).setOnClickListener(v -> hideSuccess());
    }

    private void startPulse() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f, 1f);
        pulseAnimator.setDuration(2400L);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.addUpdateListener(animation ->
                statusDot.setAlpha((float) animation.getAnimatedValue()));
        pulseAnimator.start();
    }

    // --- состояние экрана ---

    private void refreshStatus() {
        boolean configured = store.isConfigured();
        statusTitle.setText(configured ? R.string.status_ready : R.string.status_not_configured);
        statusSubtitle.setText(configured ? store.getEmail() : getString(R.string.status_hint));
        statusDot.setBackgroundResource(configured ? R.drawable.dot_ok : R.drawable.dot_warn);
        statSent.setText(String.valueOf(store.getSentCount()));
        statFiles.setText(String.valueOf(store.getTotalFiles()));

        if (TextUtils.isEmpty(editTo.getText()) && !store.getDefaultTo().isEmpty()) {
            editTo.setText(store.getDefaultTo());
        }
    }

    private void restoreDraft() {
        if (!store.getDraftSubject().isEmpty()) {
            editSubject.setText(store.getDraftSubject());
        }
        if (!store.getDraftBody().isEmpty()) {
            editBody.setText(store.getDraftBody());
        }
        String defaultTo = store.getDefaultTo();
        if (!store.getDraftTo().isEmpty()) {
            editTo.setText(store.getDraftTo());
        } else if (!defaultTo.isEmpty()) {
            editTo.setText(defaultTo);
        }
    }

    private void saveDraft() {
        store.saveDraft(text(editTo), text(editSubject), text(editBody));
    }

    // --- вложения ---

    private void addAttachment(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Не все провайдеры поддерживают persistable-разрешения.
        }
        for (Attachment existing : attachments) {
            if (existing.uri.equals(uri)) {
                return;
            }
        }
        attachments.add(Attachment.from(this, uri));
        renderAttachments();
    }

    private void renderAttachments() {
        attachmentsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < attachments.size(); i++) {
            final Attachment attachment = attachments.get(i);
            View row = inflater.inflate(R.layout.item_attachment, attachmentsContainer, false);

            TextView name = row.findViewById(R.id.fileName);
            TextView meta = row.findViewById(R.id.fileMeta);
            ImageButton remove = row.findViewById(R.id.btnRemove);

            name.setText(attachment.name);
            meta.setText(formatSize(attachment.size) + "  ·  " + shortMime(attachment.mime));
            remove.setOnClickListener(v -> {
                attachments.remove(attachment);
                renderAttachments();
            });

            attachmentsContainer.addView(row);
            row.setAlpha(0f);
            row.animate().alpha(1f).setDuration(220L).setStartDelay(i * 35L).start();
        }

        int count = attachments.size();
        attachmentsCount.setText(String.valueOf(count));
        attachmentsTitle.setText(count == 0
                ? getString(R.string.attachments_empty)
                : pluralFiles(count));
    }

    private String shortMime(String mime) {
        if (mime == null || mime.isEmpty()) {
            return "FILE";
        }
        String[] parts = mime.split("/");
        String value = parts.length > 1 ? parts[1] : parts[0];
        value = value.replaceAll("[^A-Za-z0-9.+-]", "");
        return value.isEmpty() ? "FILE" : value.toUpperCase(Locale.ROOT);
    }

    private String formatSize(long size) {
        if (size < 0L) {
            return getString(R.string.attachment_unknown_size);
        }
        if (size < 1024L) {
            return size + " Б";
        }
        if (size < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f КБ", size / 1024f);
        }
        return String.format(Locale.getDefault(), "%.1f МБ", size / (1024f * 1024f));
    }

    private String pluralFiles(int count) {
        int last = count % 10;
        int lastTwo = count % 100;
        if (last == 1 && lastTwo != 11) {
            return count + " файл";
        }
        if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) {
            return count + " файла";
        }
        return count + " файлов";
    }

    // --- отправка ---

    private void attemptSend() {
        if (sending) {
            return;
        }
        tilTo.setError(null);

        if (!store.isConfigured()) {
            showSnack(R.string.error_no_config, true);
            return;
        }

        String to = text(editTo).trim();
        if (to.isEmpty() || !validRecipients(to)) {
            tilTo.setError(getString(R.string.error_email));
            tilTo.requestFocus();
            return;
        }
        String subject = text(editSubject).trim();
        if (subject.isEmpty()) {
            subject = getString(R.string.subject_empty);
        }
        String body = text(editBody);

        if (!NetworkUtil.isOnline(this)) {
            showSnack(R.string.error_no_network, false);
            return;
        }

        List<DataSource> sources = new ArrayList<>();
        for (Attachment attachment : attachments) {
            sources.add(attachment.toDataSource(this));
        }
        int files = sources.size();

        GmailSender.Config config = new GmailSender.Config(
                store.getEmail(), store.getPassword(), store.getSenderName(),
                store.getHost(), store.getPort(), store.isSsl());

        setSending(true);
        GmailSender.send(config, to, subject, body, sources, new GmailSender.Callback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> onSent(files));
            }

            @Override
            public void onError(GmailSender.ErrorKind kind, String detail) {
                runOnUiThread(() -> onSendError(kind, detail));
            }
        });
    }

    private boolean validRecipients(String value) {
        for (String part : value.split("[,;]")) {
            String candidate = part.trim();
            if (candidate.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(candidate).matches()) {
                return false;
            }
        }
        return true;
    }

    private void setSending(boolean active) {
        sending = active;
        btnSend.setEnabled(!active);
        btnSend.setAlpha(active ? 0.6f : 1f);
        progressOverlay.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    private void onSent(int files) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setSending(false);
        store.registerSent(files);
        attachments.clear();
        renderAttachments();
        editSubject.setText("");
        editBody.setText("");
        saveDraft();
        refreshStatus();
        showSuccess(files);
    }

    private void onSendError(GmailSender.ErrorKind kind, String detail) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setSending(false);
        int message;
        switch (kind) {
            case AUTH:
                message = R.string.error_auth;
                break;
            case NETWORK:
                message = R.string.error_connection;
                break;
            case MESSAGE:
                message = R.string.error_bad_settings;
                break;
            default:
                message = R.string.error_bad_settings;
                break;
        }

        // Показываем технические детали, чтобы по логу/диалогу сразу было видно,
        // какой порт и какой хост не ответил.
        String text = getString(message);
        if (kind == GmailSender.ErrorKind.NETWORK) {
            text += "\n\n" + getString(R.string.error_dialog_hint);
        }
        if (detail != null && !detail.trim().isEmpty()) {
            text += "\n\n" + getString(R.string.error_details) + ":\n" + detail;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_dialog_title)
                .setMessage(text)
                .setPositiveButton(R.string.action_settings,
                        (dialog, which) -> startActivity(new Intent(this, SettingsActivity.class)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- экраны состояния ---

    private void showSuccess(int files) {
        successDetail.setText(getString(R.string.success_attachments, files));
        handler.removeCallbacks(hideSuccessRunnable);

        successOverlay.setVisibility(View.VISIBLE);
        successOverlay.setAlpha(0f);
        successOverlay.animate().alpha(1f).setDuration(220L).start();

        successCard.setAlpha(0f);
        successCard.setScaleX(0.82f);
        successCard.setScaleY(0.82f);
        successCard.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .setDuration(520L)
                .start();

        successRing.setScaleX(0.3f);
        successRing.setScaleY(0.3f);
        successRing.animate()
                .scaleX(1f).scaleY(1f)
                .setStartDelay(120L)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .setDuration(600L)
                .start();

        confettiView.start();
        handler.postDelayed(hideSuccessRunnable, 5000L);
    }

    private void hideSuccess() {
        handler.removeCallbacks(hideSuccessRunnable);
        if (successOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        successOverlay.animate().alpha(0f).setDuration(200L)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        successOverlay.setVisibility(View.GONE);
                    }
                }).start();
    }

    private void showSnack(int messageRes, boolean withSettingsAction) {
        Snackbar snackbar = Snackbar.make(root, messageRes, Snackbar.LENGTH_LONG);
        snackbar.setBackgroundTint(ContextCompat.getColor(this, R.color.snack_bg));
        snackbar.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        snackbar.setActionTextColor(ContextCompat.getColor(this, R.color.mint));
        if (withSettingsAction) {
            snackbar.setAction(R.string.action_settings,
                    v -> startActivity(new Intent(this, SettingsActivity.class)));
        }
        snackbar.show();
    }

    private static String text(TextView view) {
        return view.getText() == null ? "" : view.getText().toString();
    }

    // --- жизненный цикл ---

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveDraft();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideSuccessRunnable);
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (successOverlay.getVisibility() == View.VISIBLE) {
            hideSuccess();
            return;
        }
        super.onBackPressed();
    }
}
