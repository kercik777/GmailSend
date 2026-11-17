# ✨ Aurora Mail

**Дизайн — новый, код отправки — оригинальный из `main`.**

По просьбе: вся логика отправки возвращена к рабочему варианту из исходного проекта.
Никаких экранов настроек, полей «SMTP host», портов и переключателей SSL — **всё задано
в коде**, как было изначально. Визуальная часть (экраны, иконки, анимации, конфетти)
осталась полностью нетронутой.

> 📱 Макет дизайна: `design-preview/index.html` (или живой предпросмотр в Arena).

---

## Что именно вернулось из `main`

| Файл | Состояние |
| --- | --- |
| `GmailSender.java` | **Восстановлен дословно** из `main`: `jakarta.mail.*`, 4 SMTP-конфигурации с предпроверкой соединения, mailcap, HTML-письмо, вложения через `FileDataSource` |
| `FileUtil.java` | Восстановлен дословно (`convertUriToFilePath`) |
| `SketchwareUtil.java` | Восстановлен дословно (`showMessage`) |
| `MainActivity.java` | Та же логика: `ACTION_GET_CONTENT` + `startActivityForResult`, `ClipData`, список путей `selectedFiles`, вызов `GmailSender.sendEmail(...)`, очистка списка после успеха. Привязана к новым id дизайна |
| Константы | Получатель `bayramsanazarow1997@gmail.com`, тема `tema` — в `MainActivity.java`; почта и пароль приложения — в `GmailSender.java` |

Добавлены только безопасные мелочи в `MainActivity`: проверка на пустой результат
пикера (в оригинале `selectedFiles.add(_filePath.get(0))` падало при отмене выбора),
фильтр `null`-путей, а также отрисовка вложений, счётчиков и экрана успеха в новом дизайне.

Удалены: `SettingsActivity`, `SettingsStore`, `NetworkUtil`, `Attachment`, `UriDataSource`,
`activity_settings.xml`, `androidx.security:security-crypto`.

Иконка «шестерёнка» и карточка статуса теперь открывают **информационный диалог**
(отправитель / получатель / тема) вместо экрана настроек.

---

## ⚠️ Пароль приложения снова в коде

В `GmailSender.java`:

```java
private static final String USERNAME = "zayavki1247@gmail.com";
private static final String PASSWORD = "ompootzqcobdpjpc";
```

Это точные значения из оригинала. Два важных момента:

1. Пароль **остаётся в истории git** (коммит `4dde48e`) — он уже был там до меня.
   Для личного приложения это допустимо, но в публичный репозиторий так лучше не делать.
2. Если ты **отзывал старый пароль** по моему предыдущему совету — замени `PASSWORD`
   на новый 16-значный пароль приложения и пересобери APK. Иначе Gmail ответит
   `AuthenticationFailedException` (логин не пройдёт).

Поменять получателя/тему можно константами `RECIPIENT` / `SUBJECT` в `MainActivity.java`
(они же подставляются в поля формы при запуске).

---

## Зависимости

```groovy
implementation 'com.sun.mail:jakarta.mail:2.0.2'
implementation 'com.sun.activation:jakarta.activation:2.0.1'
```

Это те же библиотеки, что использовались в исходнике. В `app/build.gradle` есть
переключатель `useOriginalJars`: поставь `true`, чтобы брать jar'ы из `app/libs/`
(`jakarta.mail-2.0.2.jar` + `jakarta.activation-2.0.1.jar`) — ровно как в оригинале;
тогда Maven-зависимости не подключаются и дублирования классов не будет.

Манифест тоже приведён к оригинальному поведению: `READ_EXTERNAL_STORAGE`,
`requestLegacyExternalStorage="true"`, `usesCleartextTraffic="true"`.
При этом **не** восстановлены несуществующие `.SketchApplication` / `.DebugActivity`
(из-за них исходный манифест падал при запуске).

---

## Сборка

```bash
./gradlew assembleDebug      # Windows: gradlew.bat assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, Android SDK 34, Gradle Wrapper 8.13 уже в репозитории.

---

## Если снова «не соединяется»

При ошибке приложение показывает диалог с **точным текстом исключения** из оригинального
`GmailSender.Callback.onError(...)`. Оригинал перебирает 4 конфигурации:
`smtp.gmail.com:587` (STARTTLS), `:465` (SSL), `smtp.googlemail.com:587`, `:465` —
по 10 секунд на каждую, так что первая попытка может занять до ~40 секунд.

Частые причины:
- пароль приложения отозван / неверный → замени константу `PASSWORD`;
- провайдер блокирует Gmail SMTP → попробуйте VPN или мобильный интернет;
- нет двухэтапной аутентификации в Google Аккаунте → пароль приложения не создаётся;
- старый APK: обязательно **пересоберите и переустановите** приложение после обновления.

Пришлите текст из диалога — по нему сразу видно, на каком шаге всё стопорится.

---

## Структура

```
app/src/main/java/com/gmail/
├── MainActivity.java       логика из main, привязанная к новому дизайну
├── GmailSender.java        ОРИГИНАЛ из main (jakarta.mail, 4 SMTP-конфигурации)
├── FileUtil.java           ОРИГИНАЛ из main (Uri → путь)
├── SketchwareUtil.java     ОРИГИНАЛ из main
├── AuroraView.java         новый анимированный фон
└── ConfettiView.java       новые конфетти на экране успеха
app/src/main/res/           layout / drawable / values / mipmap — новый дизайн
design-preview/             интерактивный HTML-макет интерфейса
```
