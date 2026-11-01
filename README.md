# NET MUSIC

**Твоя музыка. В одном месте.**

NET MUSIC — нативное Android-приложение на Kotlin и Jetpack Compose. Это не WebView и не HTML-обёртка: интерфейс и аудиоплеер работают средствами Android.

## Что уже реализовано

- Локальная медиатека Android с разрешениями и обновлением списка.
- Воспроизведение через AndroidX Media3, фоновый PlaybackService и системные media controls.
- Мини-плеер, полноэкранный плеер, очередь, перемешивание, повтор и скорость.
- Единый поиск по каталогам Audius и Jamendo через backend.
- Загрузка композиций Jamendo только когда провайдер явно разрешает скачивание.
- Отдельная страница «Моя музыка»: все треки, загрузки, избранное, история и пользовательские плейлисты.
- Аккаунт NET MUSIC Cloud: регистрация по email, вход, защищённая сессия и синхронизация коллекции между устройствами.
- Spotify OAuth для подключения аккаунта; доступность Spotify Web API зависит от правил и режима приложения Spotify.

## Облако

Backend использует PostgreSQL. В облаке хранятся аккаунт и данные музыкальной библиотеки: избранное, история, плейлисты и список загрузок. Сами аудиофайлы остаются на телефоне, чтобы локальная музыка продолжала работать офлайн. Для хранения и выдачи самих файлов через интернет потребуется отдельное S3-совместимое объектное хранилище.

### Переменные окружения Render

Добавь в Environment backend-сервиса:

- `DATABASE_URL` — Internal Database URL от Render PostgreSQL.
- `CLOUD_JWT_SECRET` — случайный секрет минимум 32 символа (лучше 64+).
- `JAMENDO_CLIENT_ID` — Client ID приложения Jamendo.
- Уже используемые Spotify-переменные: `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `SPOTIFY_REDIRECT_URI`, `APP_REDIRECT_URI`.

Не добавляй секреты в GitHub и не отправляй их в чат. После настройки `GET /api/cloud/status` должен вернуть `available: true`. Таблицы облака создаются автоматически при первом запросе.

## Технологии

- Android: Kotlin, Jetpack Compose, Material 3, AndroidX Media3, Coil.
- Backend: Node.js, Express 5, PostgreSQL.
- Cloud auth: scrypt password hashing, signed bearer tokens.
- Android minimum: API 26 (Android 8.0).

## Сборка Android

Открой проект в Android Studio и запусти:

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Принципы интеграций

NET MUSIC использует официально доступные API и разрешённые ссылки загрузки. Приложение не обходит DRM, подписки или ограничения провайдеров.
