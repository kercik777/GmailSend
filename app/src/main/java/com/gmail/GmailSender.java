package com.gmail;

import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.activation.CommandMap;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.activation.MailcapCommandMap;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

public class GmailSender {
	
	public interface Callback {
		void onSuccess();
		void onError(String errorMessage);
	}
	
	private static final String TAG = "GmailSender";
	private static final String USERNAME = "zayavki1247@gmail.com";
	private static final String PASSWORD = "ompootzqcobdpjpc";
	
	// Вспомогательный класс конфигурации SMTP-соединения.
	private static class SMTPConfig {
		String host;
		String port;
		boolean useStartTLS;
		boolean useSSL;
		
		SMTPConfig(String host, String port, boolean useStartTLS, boolean useSSL) {
			this.host = host;
			this.port = port;
			this.useStartTLS = useStartTLS;
			this.useSSL = useSSL;
		}
	}
	
	/**
     * Отправляет email с заданными параметрами.
     *
     * @param to             адрес получателя
     * @param subject        тема письма
     * @param text           текст письма (будет отправлен в виде HTML)
     * @param selectedFiles  список путей к файлам, которые прикрепляются к письму
     * @param callback       callback с результатом отправки
     */
	public static void sendEmail(final String to, final String subject, final String text,
	final List<String> selectedFiles, final Callback callback) {
		final Executor executor = Executors.newSingleThreadExecutor();
		
		executor.execute(() -> {
			// Массив возможных SMTP-конфигураций для Gmail.
			SMTPConfig[] configs = new SMTPConfig[]{
				new SMTPConfig("smtp.gmail.com", "587", true, false),
				new SMTPConfig("smtp.gmail.com", "465", false, true),
				new SMTPConfig("smtp.googlemail.com", "587", true, false),
				new SMTPConfig("smtp.googlemail.com", "465", false, true)
			};
			
			SMTPConfig workingConfig = null;
			Session workingSession = null;
			
			// Предварительная проверка: для каждой конфигурации пытаемся установить соединение.
			for (SMTPConfig config : configs) {
				try {
					Log.d(TAG, "Проверка конфигурации: " + config.host + ":" + config.port);
					Properties props = new Properties();
					props.put("mail.smtp.host", config.host);
					props.put("mail.smtp.port", config.port);
					props.put("mail.smtp.auth", "true");
					
					if (config.useStartTLS) {
						props.put("mail.smtp.starttls.enable", "true");
						props.put("mail.smtp.ssl.protocols", "TLSv1.2");
					}
					if (config.useSSL) {
						props.put("mail.smtp.ssl.enable", "true");
						props.put("mail.smtp.socketFactory.port", config.port);
						props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
						props.put("mail.smtp.socketFactory.fallback", "false");
						props.put("mail.smtp.ssl.protocols", "TLSv1.2");
					}
					
					Session session = Session.getInstance(props, new Authenticator() {
						@Override
						protected PasswordAuthentication getPasswordAuthentication() {
							return new PasswordAuthentication(USERNAME, PASSWORD);
						}
					});
					
					// Опционально: если mailcap-обработчики не зарегистрированы, их можно зарегистрировать здесь.
					MailcapCommandMap mc = (MailcapCommandMap) CommandMap.getDefaultCommandMap();
					mc.addMailcap("multipart/mixed;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed");
					mc.addMailcap("multipart/alternative;; x-java-content-handler=com.sun.mail.handlers.multipart_alternative");
					CommandMap.setDefaultCommandMap(mc);
					
					// Пытаемся установить соединение с SMTP-сервером с таймаутом 10 секунд.
					Callable<Boolean> checkConnection = () -> {
						Transport transport = session.getTransport("smtp");
						try {
							transport.connect();
							return true;
						} finally {
							transport.close();
						}
					};
					
					Future<Boolean> future = Executors.newSingleThreadExecutor().submit(checkConnection);
					if (future.get(10, TimeUnit.SECONDS)) {
						workingConfig = config;
						workingSession = session;
						Log.d(TAG, "Рабочая конфигурация найдена: " + config.host + ":" + config.port);
						break;
					}
				} catch (TimeoutException te) {
					Log.e(TAG, "Таймаут проверки конфигурации " + config.host + ":" + config.port, te);
				} catch (Exception e) {
					Log.e(TAG, "Ошибка проверки конфигурации " + config.host + ":" + config.port, e);
				}
			}
			
			if (workingConfig == null) {
				if (callback != null) {
					callback.onError("Ни одна SMTP-конфигурация не ответила.");
				}
				return;
			}
			
			try {
				// Формируем письмо
				MimeMessage message = new MimeMessage(workingSession);
				message.setFrom(new InternetAddress(USERNAME, "Моё Android-приложение"));
				message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
				message.setSubject(subject);
				
				// Формируем multipart-содержимое
				MimeMultipart multipart = new MimeMultipart();
				
				// Первая часть – текстовое HTML-содержимое
				MimeBodyPart textPart = new MimeBodyPart();
				String htmlContent = "<html><body>" +
				"<h2 style='color:#0066cc;'>" + subject + "</h2>" +
				"<p>" + text.replace("\n", "<br>") + "</p>" +
				"<p><small>Отправлено из приложения</small></p>" +
				"</body></html>";
				textPart.setContent(htmlContent, "text/html; charset=utf-8");
				multipart.addBodyPart(textPart);
				
				// Добавляем вложения, если имеются файлы из списка
				if (selectedFiles != null && !selectedFiles.isEmpty()) {
					for (String filePath : selectedFiles) {
						if (filePath != null && !filePath.trim().isEmpty()) {
							File file = new File(filePath);
							if (file.exists()) {
								MimeBodyPart attachmentPart = new MimeBodyPart();
								DataSource source = new FileDataSource(file);
								attachmentPart.setDataHandler(new DataHandler(source));
								attachmentPart.setFileName(file.getName());
								multipart.addBodyPart(attachmentPart);
							}
						}
					}
				}
				
				message.setContent(multipart);
				message.addHeader("Precedence", "bulk");
				message.addHeader("X-Mailer", "MyApp");
				
				// Отправляем сообщение
				Transport.send(message);
				
				if (callback != null) {
					callback.onSuccess();
				}
			} catch (Exception e) {
				Log.e(TAG, "Ошибка отправки письма", e);
				if (callback != null) {
					callback.onError(e.getMessage() != null ? e.getMessage() : "Неизвестная ошибка");
				}
			}
		});
	}
}