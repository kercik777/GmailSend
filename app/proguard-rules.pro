# Если минификация будет включена, сохраняем классы Jakarta Mail и модели приложения.
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-keep class com.sun.mail.** { *; }
-keep class com.sun.activation.** { *; }
-keep class com.gmail.** { *; }
-dontwarn jakarta.mail.**
-dontwarn jakarta.activation.**
