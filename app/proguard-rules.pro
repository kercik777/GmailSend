# Если минификация будет включена, сохраняем классы JavaMail и модели приложения.
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.mail.** { *; }
-keep class com.gmail.** { *; }
-dontwarn javax.mail.**
-dontwarn javax.activation.**
