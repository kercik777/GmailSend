package com.gmail;

import android.content.ContentResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.activation.DataSource;

/** Потоковое чтение вложения прямо из content:// Uri без промежуточных файлов. */
public final class UriDataSource implements DataSource {

    private final ContentResolver resolver;
    private final Attachment attachment;

    UriDataSource(ContentResolver resolver, Attachment attachment) {
        this.resolver = resolver;
        this.attachment = attachment;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        InputStream stream = resolver.openInputStream(attachment.uri);
        if (stream == null) {
            throw new IOException("Не удалось открыть вложение: " + attachment.name);
        }
        return stream;
    }

    @Override
    public OutputStream getOutputStream() {
        throw new UnsupportedOperationException("Вложение доступно только для чтения");
    }

    @Override
    public String getContentType() {
        return attachment.mime;
    }

    @Override
    public String getName() {
        return attachment.name;
    }
}
