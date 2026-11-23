# CI

Сборка APK идёт в двух зеркальных репозиториях одинаковыми workflow:

- GitHub Actions — `.github/workflows/android.yml`
- Forgejo Actions — `.forgejo/workflows/android.yml`

Оба запускаются на `push` в `main`, на `push` тега `v*` и вручную
(`workflow_dispatch`). На каждый запуск собирается APK, прогоняются JVM-тесты
эмулятора терминала (`:app:testDebugUnitTest`), артефакты складываются в
загрузки workflow; по тегу `v*` дополнительно создаётся релиз с APK.

Gradle в CI ставится отдельно — в репозитории нет `gradle/wrapper/`, `gradlew`
там лишь тонкая обёртка над локальной установкой, поэтому шаг
`gradle/actions/setup-gradle@v4` кладёт в PATH Gradle 8.11.1. Кэш `~/.gradle/caches`
и `~/.gradle/wrapper` восстанавливается `actions/cache@v4` до установки Gradle,
ключ — по `**/*.gradle*` и `**/gradle.properties`.

## Forgejo: откуда берутся actions

На `code.forgejo.org` зазеркалены `checkout`, `cache`, `upload-artifact` — они
подключены полными URL. `setup-java`, `setup-android` и `setup-gradle` там
отсутствуют (404), поэтому в `.forgejo/workflows/android.yml` они берутся с
GitHub по полному URL. Если ваш runner не объявляет метку `ubuntu-latest`,
замените `runs-on` на его метку (обычно `docker`).

## Секреты

Задаются в настройках репозитория (Settings → Secrets and variables → Actions).
Секреты не передаются между GitHub и Forgejo: `KEYSTORE_BASE64` и
`KEYSTORE_PROPERTIES` нужно завести в обоих репозиториях.

### `KEYSTORE_BASE64` — сам keystore в base64

```bash
base64 -w0 ~/.lynk/lynk-release.jks
```

Получившуюся строку (одной строкой, без переводов строк) положить в секрет.

### `KEYSTORE_PROPERTIES` — содержимое `keystore.properties` целиком

Пароли и alias берутся из вашего локального `keystore.properties`. Путь
`storeFile` — **не** путь с рабочей машины: в CI keystore распаковывается внутрь
чекаута, поэтому путь указывается относительно рабочей директории сборки.

Так как signing config объявлен в `app/build.gradle.kts`, относительный
`storeFile` резолвится относительно каталога модуля `:app`, а CI распаковывает
`release.jks` в `app/`. Поэтому секрет должен содержать:

```properties
storeFile=release.jks
storePassword=<пароль хранилища>
keyAlias=lynk
keyPassword=<пароль ключа>
```

Если держать `release.jks` в корне репозитория, тот же файл подойдёт с
`storeFile=../release.jks`.

### `FORGEJO_TOKEN` — только для Forgejo

Токен с правом записи в репозиторий (scope `write:repository`), нужен для
создания релиза через API Forgejo. Если секрет не задан, шаг релиза пропускается
без ошибки — сборка и загрузка артефактов всё равно выполняются.

На GitHub релиз создаётся автоматически: встроенный `GITHUB_TOKEN` под рукой,
а workflow запрашивает `permissions: contents: write`.

## Если подписи нет

Когда `KEYSTORE_BASE64` пуст (или пуст `KEYSTORE_PROPERTIES`), шаг подготовки
подписи пишет в лог `подпись недоступна, собираю debug` и собирается
`:app:assembleDebug` вместо `:app:assembleRelease`. Тесты (`:app:testDebugUnitTest`)
запускаются в обоих случаях. Содержимое keystore и пароли в лог не попадают.

## Где забрать результат

- **Артефакты workflow** — `apk` (все собранные APK) и `test-results`
  (JUnit XML из `:app:testDebugUnitTest`). Доступны на странице запуска
  (Actions → нужный run → Artifacts).
- **Релизы** — создаются только при push тега `v*`: на GitHub через `gh release
  create`, на Forgejo через API. Версия приложения берётся из
  `app/build.gradle.kts`, отдельно её нигде задавать не нужно.
