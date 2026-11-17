package com.gmail;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import jakarta.activation.DataSource;

/**
 * Вложение, полученное через системный файловый picker (SAF).
 *
 * Мы намеренно не превращаем content:// Uri в путь к файлу: на Android 10+
 * scoped storage делает это невозможным. Вместо этого читаем содержимое
 * через ContentResolver (см. {@link UriDataSource}).
 */
public final class Attachment {

    public final Uri uri;
    public final String name;
    public final long size;
    public final String mime;

    private Attachment(Uri uri, String name, long size, String mime) {
        this.uri = uri;
        this.name = name;
        this.size = size;
        this.mime = mime;
    }

    public static Attachment from(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        String name = "";
        long size = -1L;

        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex);
                }
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {
            // Некоторые провайдеры не отдают метаданные — это не критично.
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                }
            }
        }

        if (name == null || name.trim().isEmpty()) {
            name = "attachment_" + Math.abs(uri.hashCode());
        }
        name = name.replaceAll("[\\r\\n\\t]", "_");

        String mime = null;
        try {
            mime = resolver.getType(uri);
        } catch (Exception ignored) {
        }
        if (mime == null || mime.isEmpty()) {
            mime = "application/octet-stream";
        }
        return new Attachment(uri, name, size, mime);
    }

    public DataSource toDataSource(Context context) {
        return new UriDataSource(context.getApplicationContext().getContentResolver(), this);
    }
}
