# Ю — Android TV

[![Android checks](https://github.com/Sqzzzzzzz/utv-android-tv/actions/workflows/android.yml/badge.svg)](https://github.com/Sqzzzzzzz/utv-android-tv/actions/workflows/android.yml)

Приватный личный клиент u-tv.ru для Android TV / Google TV. Текущая версия исходников — **0.5.0**, пакет `dev.mark.utvremote`. Поддерживает «Мама в 16» и «Маменькин сынок», управление пультом, историю просмотра, каталог с кешем, выбор качества и плеер с подтверждаемой перемоткой и HD-превью из видео.

## Проверка проекта

JDK 17, Android SDK Platform 36 и Build Tools 36.0.0. В Android Studio открой корень репозитория.

```sh
./gradlew --no-daemon -Putv.ci=true assembleDebug testDebugUnitTest lintDebug
```

На Windows используй `gradlew.bat`.

Флаг `-Putv.ci=true` позволяет проверить проект без личного ключа подписи. Полученный APK подписан временным ключом и **не подходит для обновления установленной на телевизоре версии**. Автосборка сохраняет отчёты тестов и lint, APK не публикует.

Для совместимого обновления требуется исходный `signing/prototype-debug.keystore`, который хранится отдельно и исключён из Git. Обычная сборка без CI-флага использует только этот ключ.

## Автоматическая проверка

GitHub Actions запускает сборку, тесты и Android lint при изменениях в `main` и в pull request. Отчёты находятся в **Actions → Android checks → Artifacts**.

Для независимого ИИ-ревью включи **Automatic review** для этого репозитория в настройках Codex. Правила проверки находятся в [AGENTS.md](AGENTS.md). Инструкция: [GITHUB-SETUP-RU.md](GITHUB-SETUP-RU.md).

Проверки не гарантируют работу конкретного декодера TCL или доступность серверов сайта. Превью реального видео и управление пультом дополнительно проверяются на телевизоре.

История реализации и проверки: [VERIFICATION-RU.md](VERIFICATION-RU.md). Проект не является официальным приложением телеканала.
