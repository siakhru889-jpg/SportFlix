# Sportflix IPTV для Windows (первая версия)

Отдельное приложение под Windows (Kotlin + Compose Desktop). Общая с Android-версией логика: плейлисты, подписка по коду,
программа передач, сериалы и сезоны, афиша, поиск внутри раздела, отметки просмотра, избранное.

## Что нужно на компьютере
1. **JDK 17** (например, Temurin 17).
2. **VLC 64-bit** (videolan.org) — им воспроизводится видео (поддерживает User-Agent и referer из плейлистов).
3. **IntelliJ IDEA** (Community подойдёт) — откройте папку проекта, дождитесь импорта Gradle.

## Запуск и сборка
- Запуск: в IDEA откройте задачу Gradle `compose desktop → run` или в терминале `gradlew run`
  (если нет `gradlew`, в IDEA: Gradle → Tasks → compose desktop → run).
- Папка с готовым приложением без установщика: задача `createDistributable` (результат в `build/compose/binaries/main/app`, внутри `SportflixIPTV.exe`).
- Установщик `.msi`/`.exe`: задачи `packageMsi` / `packageExe` (нужен WiX Toolset 3.x).

## Клавиши
Пробел — пауза, ← → — перемотка 10 с, ↑ ↓ — громкость, F — полный экран, N / P — следующий / предыдущий, Esc — назад / выход.

## Где хранятся данные
`%APPDATA%\SportflixIPTV` — настройки, отметки просмотра, избранное, кеш плейлистов и картинок.
