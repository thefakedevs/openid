# Android-релизы

Workflow `Publish release builds` работает как Android-релиз в `lavafrai/maiapp`:
публикация GitHub Release запускает сборку APK, а готовый подписанный APK
прикрепляется к этому релизу вместе с SHA-256.

## Настроить один раз

В **Settings → Secrets and variables → Actions → New repository secret** добавить:

| Secret | Значение |
| --- | --- |
| `ANDROID_KEYSTORE_B64` | Keystore в base64 |
| `android_key_alias` | Alias ключа |
| `android_key_password` | Пароль ключа |
| `android_keystore_password` | Пароль keystore |

Использовать ключ, которым подписаны предыдущие APK `ru.lavafrai.openid`, чтобы
новая версия могла устанавливаться поверх старой. Keystore и пароли в Git не добавлять.
Workflow проверяет подпись перед публикацией и останавливается, если секретов нет.

## Выпустить версию

1. В `gradle.properties` обновить `openid.version` и `openid.versionCode`.
   Код вычисляется как `major * 1000000 + minor * 1000 + patch`.
   Например, `1.2.3` → `1002003`. Minor и patch должны быть меньше 1000,
   итоговый код — от 1 до 2100000000.
2. Закоммитить изменения версии и создать тег `v1.2.3` или `1.2.3` на этом коммите.
3. Создать и опубликовать GitHub Release для тега. Workflow проверит совпадение
   версии в исходниках с тегом, соберёт APK и загрузит `openid-1.2.3.apk` и
   `openid-1.2.3.apk.sha256` в assets релиза.

## Ручной запуск

**Actions → Publish release builds → Run workflow**:

- `tag` — версия, например `v1.2.3`.
- `upload = false` — сборка выбранной ветки/тега с указанной версией; результат
  доступен только как workflow artifact `android-release`.
- `upload = true` — сборка именно указанного тега и загрузка APK в уже существующий
  GitHub Release. Версия в исходниках должна совпадать с тегом.

Секреты подписи нужны и для ручного запуска. Без них можно проверить сборку
локально: `./gradlew ktlintCheck :app:assembleRelease` создаст unsigned APK,
который workflow не публикует. Для локальной подписи используются переменные
`ANDROID_KEYSTORE` (абсолютный путь), `ANDROID_KEYSTORE_KEY`,
`ANDROID_KEYSTORE_KEY_PASSWORD` и `ANDROID_KEYSTORE_PASSWORD`.
