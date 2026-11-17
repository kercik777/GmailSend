package com.gmail;

import android.Manifest;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Экран в новом дизайне Aurora Mail, но логика отправки — ровно как в
 * исходном проекте из ветки main:
 *
 *  - получатель, тема, почта и пароль приложения заданы константами в коде;
 *  - файлы выбираются через ACTION_GET_CONTENT и превращаются в пути
 *    через FileUtil.convertUriToFilePath(...);
 *  - отправка идёт через GmailSender.sendEmail(...) из оригинала.
 *
 * Никаких экранов настроек и полей SMTP: всё прописано в коде.
 */
public class MainActivity extends AppCompatActivity {

    public final int REQ_CD_PICK = 101;
    public final int REQ_CD_PICK2 = 102;

    // === Всё, что в оригинале было зашито в коде ===
    public static final String RECIPIENT = "bayramsanazarow1997@gmail.com";
    public static final String SUBJECT = "tema";
    public static final String SENDER = "zayavki1247@gmail.com";

    private Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
    private Intent pick2 = new Intent(Intent.ACTION_GET_CONTENT);

    private List<String> selectedFiles = new ArrayList<>();

    private TextView statusTitle;
    private TextView statusSubtitle;
    private View statusDot;
    private View statusCard;
    private TextView statSent;
    private TextView statFiles;
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideSuccessRunnable = this::hideSuccess;

    private ValueAnimator pulseAnimator;
    private boolean sending;
    private int sentCount;
    private int filesSent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initialize(savedInstanceState);
        initializeLogic();

        // Как в оригинале: запрашиваем доступ к хранилищу для FileUtil.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1000);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1000) {
            initializeLogic();
        }
    }

    private void initialize(Bundle savedInstanceState) {
        statusCard = findViewById(R.id.statusCard);
        statusTitle = findViewById(R.id.statusTitle);
        statusSubtitle = findViewById(R.id.statusSubtitle);
        statusDot = findViewById(R.id.statusDot);
        statSent = findViewById(R.id.statSent);
        statFiles = findViewById(R.id.statFiles);
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

        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pick2.setType("*/*");
        pick2.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        statusTitle.setText(R.string.status_ready);
        statusSubtitle.setText(SENDER);
        statusDot.setBackgroundResource(R.drawable.dot_ok);
        updateStats();
        startPulse();

        View.OnClickListener showInfo = v -> showInfoDialog();
        findViewById(R.id.btnSettings).setOnClickListener(showInfo);
        statusCard.setOnClickListener(showInfo);

        // «Несколько» → REQ_CD_PICK, «Один файл» → REQ_CD_PICK2 (как button2/button3 в оригинале).
        findViewById(R.id.btnAddMany).setOnClickListener(v ->
                startActivityForResult(pick, REQ_CD_PICK));
        findViewById(R.id.btnAddOne).setOnClickListener(v ->
                startActivityForResult(pick2, REQ_CD_PICK2));

        btnSend.setOnClickListener(v -> sendMail());
        successOverlay.setOnClickListener(v -> hideSuccess());
        findViewById(R.id.btnSuccessClose).setOnClickListener(v -> hideSuccess());

        renderAttachments();
    }

    /** Значения из кода — в исходнике метод был пустым, здесь он подставляет константы в поля. */
    private void initializeLogic() {
        if (text(editTo).isEmpty()) {
            editTo.setText(RECIPIENT);
        }
        if (text(editSubject).isEmpty()) {
            editSubject.setText(SUBJECT);
        }
    }

    // === Отправка: вызов оригинального GmailSender ===

    private void sendMail() {
        if (sending) {
            return;
        }
        String to = text(editTo).trim().isEmpty() ? RECIPIENT : text(editTo).trim();
        String subject = text(editSubject).trim().isEmpty() ? SUBJECT : text(editSubject).trim();
        String body = text(editBody);
        final int fileCount = selectedFiles.size();

        setSending(true);
        GmailSender.sendEmail(to, subject, body, selectedFiles, new GmailSender.Callback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    // Как в оригинале: очищаем список выбранных файлов после успешной отправки.
                    selectedFiles.clear();
                    renderAttachments();
                    SketchwareUtil.showMessage(getApplicationContext(), getString(R.string.success_subtitle));
                    sentCount++;
                    filesSent += fileCount;
                    updateStats();
                    setSending(false);
                    showSuccess(fileCount);
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    setSending(false);
                    showErrorDialog(errorMessage);
                });
            }
        });
    }

    // === Выбор файлов: логика из оригинального onActivityResult ===

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQ_CD_PICK:
                if (resultCode == Activity.RESULT_OK) {
                    ArrayList<String> filePath = new ArrayList<>();
                    if (data != null) {
                        if (data.getClipData() != null) {
                            for (int index = 0; index < data.getClipData().getItemCount(); index++) {
                                ClipData.Item item = data.getClipData().getItemAt(index);
                                filePath.add(FileUtil.convertUriToFilePath(getApplicationContext(), item.getUri()));
                            }
                        } else {
                            filePath.add(FileUtil.convertUriToFilePath(getApplicationContext(), data.getData()));
                        }
                    }
                    for (String path : filePath) {
                        addPath(path);
                    }
                }
                break;

            case REQ_CD_PICK2:
                if (resultCode == Activity.RESULT_OK) {
                    ArrayList<String> filePath = new ArrayList<>();
                    if (data != null) {
                        if (data.getClipData() != null) {
                            for (int index = 0; index < data.getClipData().getItemCount(); index++) {
                                ClipData.Item item = data.getClipData().getItemAt(index);
                                filePath.add(FileUtil.convertUriToFilePath(getApplicationContext(), item.getUri()));
                            }
                        } else {
                            filePath.add(FileUtil.convertUriToFilePath(getApplicationContext(), data.getData()));
                        }
                    }
                    // В оригинале было selectedFiles.add(_filePath.get(0)) — добавили
                    // проверку, чтобы отмена выбора не роняла приложение.
                    if (!filePath.isEmpty()) {
                        addPath(filePath.get(0));
                    }
                }
                break;

            default:
                break;
        }
        renderAttachments();
    }

    private void addPath(String path) {
        if (path == null || path.trim().isEmpty() || selectedFiles.contains(path)) {
            return;
        }
        selectedFiles.add(path);
    }

    // === Отрисовка вложений (дизайн) поверх списка путей из оригинала ===

    private void renderAttachments() {
        attachmentsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < selectedFiles.size(); i++) {
            final String path = selectedFiles.get(i);
            File file = new File(path);
            View row = inflater.inflate(R.layout.item_attachment, attachmentsContainer, false);

            TextView name = row.findViewById(R.id.fileName);
            TextView meta = row.findViewById(R.id.fileMeta);
            ImageButton remove = row.findViewById(R.id.btnRemove);

            name.setText(file.getName());
            meta.setText(formatSize(file.length()) + "  ·  " + fileExtension(file.getName()));
            remove.setOnClickListener(v -> {
                selectedFiles.remove(path);
                renderAttachments();
            });

            attachmentsContainer.addView(row);
            row.setAlpha(0f);
            row.animate().alpha(1f).setDuration(220L).setStartDelay(i * 35L).start();
        }

        int count = selectedFiles.size();
        attachmentsCount.setText(String.valueOf(count));
        attachmentsTitle.setText(count == 0
                ? getString(R.string.attachments_empty)
                : pluralFiles(count));
    }

    private String fileExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "FILE";
        }
        return name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private String formatSize(long size) {
        if (size <= 0L) {
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

    // === Состояния экрана (дизайн) ===

    private void startPulse() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f, 1f);
        pulseAnimator.setDuration(2400L);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.addUpdateListener(animation ->
                statusDot.setAlpha((float) animation.getAnimatedValue()));
        pulseAnimator.start();
    }

    private void updateStats() {
        statSent.setText(String.valueOf(sentCount));
        statFiles.setText(String.valueOf(filesSent));
    }

    private void setSending(boolean active) {
        sending = active;
        btnSend.setEnabled(!active);
        btnSend.setAlpha(active ? 0.6f : 1f);
        progressOverlay.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    private void showSuccess(int files) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        successDetail.setText(getString(R.string.success_attachments, files));
        handler.removeCallbacks(hideSuccessRunnable);

        successOverlay.setVisibility(View.VISIBLE);
        successOverlay.setAlpha(0f);
        successOverlay.animate().alpha(1f).setDuration(220L).start();

        successCard.setAlpha(0f);
        successCard.setScaleX(0.82f);
        successCard.setScaleY(0.82f);
        successCard.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .setDuration(520L).start();

        successRing.setScaleX(0.3f);
        successRing.setScaleY(0.3f);
        successRing.animate().scaleX(1f).scaleY(1f)
                .setStartDelay(120L)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .setDuration(600L).start();

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

    private void showErrorDialog(String detail) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String message = getString(R.string.error_dialog_hint);
        if (detail != null && !detail.trim().isEmpty()) {
            message += "\n\n" + getString(R.string.error_details) + ":\n" + detail;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_dialog_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Вместо экрана настроек: параметры заданы в коде, как в оригинале. */
    private void showInfoDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.info_title)
                .setMessage(getString(R.string.info_body, SENDER, RECIPIENT, SUBJECT))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static String text(TextView view) {
        return view.getText() == null ? "" : view.getText().toString();
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
