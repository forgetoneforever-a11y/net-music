# NET MUSIC

**Твоя музыка. В одном месте.**

NET MUSIC — нативное Android-приложение на Kotlin и Jetpack Compose. Это не WebView и не HTML-обёртка.

## Возможности

- Локальная медиатека Android и воспроизведение через AndroidX Media3.
- Фоновый плеер, системные media controls, мини-плеер и полноэкранный плеер.
- Единый поиск по Audius и Jamendo через backend.
- Загрузка Jamendo-треков только при наличии разрешения провайдера.
- Страница «Моя музыка»: все треки, загрузки, избранное, история и плейлисты.
- NET MUSIC Cloud: регистрация и вход по email, PostgreSQL-хранилище метаданных и синхронизация музыкальной коллекции.

## Облачное хранилище

Сейчас облако хранит аккаунт и метаданные музыкальной библиотеки: избранное, историю, плейлисты и список загрузок. Аудиофайлы остаются на устройстве для офлайн-воспроизведения. Для загрузки личных аудиофайлов в облако и выдачи их на других устройствах потребуется отдельное S3-совместимое объектное хранилище.

### Render Environment

- `DATABASE_URL` — Internal Database URL PostgreSQL в Render.
- `CLOUD_JWT_SECRET` — случайный секрет не короче 32 символов (рекомендуется 64+).
- `JAMENDO_CLIENT_ID` — Client ID приложения Jamendo.
- Spotify: `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `SPOTIFY_REDIRECT_URI`, `APP_REDIRECT_URI`.

Секреты не добавляй в GitHub и не отправляй в чат. Проверка `GET /api/cloud/status` должна вернуть `available: true`. Таблицы создаются автоматически при первом обращении к облачному API.

## Стек

Kotlin · Jetpack Compose · Material 3 · AndroidX Media3 · Node.js · Express · PostgreSQL.

Минимальная версия Android: API 26 (Android 8.0).

## Сборка APK

```bash
./gradlew assembleDebug
```

Файл: `app/build/outputs/apk/debug/app-debug.apk`.

NET MUSIC использует официальные API и разрешённые способы загрузки. Приложение не обходит DRM, подписки и ограничения провайдеров.
