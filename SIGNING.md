# Подписанная ARM64 Release APK (1.5.0)

Отдельный workflow `.github/workflows/arm64-release.yml` собирает существующий
`assembleArm64-v8aRelease`, затем подписывает APK вне Gradle. Функциональный код,
applicationId `com.econverter.app`, версия 1.5.0 и debug workflows не изменяются.
ARM64 release имеет versionCode 1704; minSdk 24, targetSdk 37.

## Сначала проверьте существующий ключ

В проекте уже предусмотрены KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS и
KEY_PASSWORD (tag workflow `release.yml`). Владелец должен проверить их наличие
в Settings → Secrets and variables → Actions и найти резервную копию ключа.
GitHub не позволяет выгрузить значение сохранённого секрета. Отсутствие доступа
Codex к списку Secrets (403) **не означает отсутствия ключа**.

Используйте существующий постоянный ключ, если он доступен и предназначен для
этого приложения. Не заменяйте его новым без необходимости. Узнайте тип хранилища
(JKS/PKCS12), alias и SHA-256 сертификата локально, с интерактивным вводом пароля:

```sh
keytool -list -v -keystore /secure/existing-release.keystore -alias YOUR_ALIAS
```

Либо проверьте сертификат ранее подписанной доверенной release APK:
`apksigner verify --verbose --print-certs existing-release.apk`.
Нужен SHA-256 **certificate**, а не public key. Ключи F-Droid, Google Play App
Signing и временный debug-ключ могут отличаться от собственного release-ключа.
Обновление установленного приложения возможно только при совместимой подписи.
Не удаляйте установленную версию ради смены ключа без сохранения нужных данных.

## Только если пригодного постоянного ключа действительно нет

Создайте ключ **на своём доверенном компьютере**, вне репозитория. Не запускайте
эту команду в Codex, GitHub Actions или общем облачном терминале:

```sh
keytool -genkeypair -keystore econverter-release.p12 -storetype PKCS12 \
  -alias econverter-release -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=eBook Converter"
```

Пароль вводится по запросу, не в аргументах команды. Используйте стойкий случайный
пароль; для PKCS12 пароль ключа обычно совпадает с паролем хранилища. Сохраните
минимум две зашифрованные резервные копии в разных местах, ограничьте права файла,
а пароль храните отдельно в менеджере паролей. Сохраните также alias, тип и
отпечаток сертификата. Потеря ключа лишает возможности выпускать совместимые
обновления. Не коммитьте ключ, base64 или пароли и не отправляйте их в чат.

## Настройка GitHub

1. Создайте Environment `release-signing` в Settings → Environments.
2. Настройте required reviewers и разрешите только `feat/rar-7z-import` и
   `arm64-test-*` tags. При единственном владельце не включайте запрет собственного
   approval без другого доступного reviewer. Доступность protection rules зависит
   от тарифа и видимости репозитория; если reviewers недоступны, ограничьте доступ
   к workflow и право запуска доверенными владельцами.
3. В этом Environment добавьте Secrets:

| Secret | Значение |
| --- | --- |
| `KEYSTORE_BASE64` | Полное хранилище ключа, закодированное base64 |
| `KEYSTORE_PASSWORD` | Пароль хранилища |
| `KEY_ALIAS` | Alias существующего release-ключа |
| `KEY_PASSWORD` | Пароль ключа (для нового PKCS12 — пароль хранилища) |

Можно повторно использовать уже настроенные repository Secrets с теми же именами;
Environment Secrets имеют приоритет. Не меняйте существующие секреты debug/release
без необходимости. Environment approval защищает именно новый workflow.

4. В Variables этого Environment добавьте:
   - `RELEASE_KEYSTORE_TYPE`: `JKS` либо `PKCS12`;
   - `RELEASE_CERT_SHA256`: 64 шестнадцатеричных символа SHA-256 сертификата
     (двоеточия допустимы). Это публичный отпечаток, не секрет. Проверяйте его по
     доверенной локальной копии ключа/APK, а не по выводу неизвестной сборки.

Пример загрузки ключа **с локального компьютера** через gh, без промежуточного
base64-файла и без вывода ключа в терминал:

```sh
python3 -c 'import base64,sys; sys.stdout.write(base64.b64encode(open(sys.argv[1],"rb").read()).decode())' \
  /secure/econverter-release.p12 | \
  gh secret set KEYSTORE_BASE64 --repo ivanniks-sudo/econverter --env release-signing

gh secret set KEYSTORE_PASSWORD --repo ivanniks-sudo/econverter --env release-signing
gh secret set KEY_ALIAS --repo ivanniks-sudo/econverter --env release-signing
gh secret set KEY_PASSWORD --repo ivanniks-sudo/econverter --env release-signing
```

Последние команды запрашивают значения интерактивно. Base64 — кодирование,
**не шифрование**. Не передавайте пароли через `--body` или shell history.

## Запуск и получение APK

Изменения workflow сначала должны быть проверены и опубликованы владельцем в
`feat/rar-7z-import` (push из Codex требует отдельного разрешения). Новый workflow
может не появиться для workflow_dispatch, пока его нет в default branch.
Слияние с master для этого не требуется: предусмотрен явный запуск через новый
тег `arm64-test-*`, указывающий на проверенный коммит feature-ветки:

```sh
# Только после публикации и проверки изменений workflow:
git tag arm64-test-1.5.0-1 <ПОЛНЫЙ_SHA_ПРОВЕРЕННОГО_КОММИТА>
git push origin refs/tags/arm64-test-1.5.0-1
```

Такой тег не запускает существующий release workflow с шаблоном `v*` и не создаёт
GitHub Release. Если workflow уже доступен в Actions, выберите **Signed ARM64
Release APK → Run workflow → feat/rar-7z-import**. Подтвердите Environment approval
только после проверки исходного коммита и workflow. Допускаются лишь потомки
проверенной версии `2c6f2920eae952782fffa287c4fc892beb43549a`.

В завершённом запуске Actions скачайте artifact `signed-arm64-release-...`:
APK, SHA256SUMS и verification.json (срок хранения 14 дней). Отчёт содержит
фактический SHA сборки, размер, сертификат, ID, версию и ABI. Подпись добавит лишь
небольшой объём к измеренным 32,08 МиБ; точный размер определяется готовым APK.
Проверьте checksum и подпись локально перед установкой. ARM64 APK предназначен
для arm64-v8a устройств, остальные архитектуры сохраняются в штатных сборках.

Workflow проверяет apksigner, certificate pin, zipalign (включая 16 KiB), manifest
и отсутствие других ABI в lib/. Ключ создаётся только локально владельцем,
декодируется в закрытый временный каталог runner после завершения Gradle,
пароли передаются apksigner через environment. Каталог удаляется при успехе/ошибке;
используется одноразовый hosted runner. В artifacts попадают только APK и публичные
отчёты. Функциональные сценарии ZIP/RAR4/RAR5/7z, сохранение и Gmail следует
повторно проверить на подписанной release APK: R8 включён штатной конфигурацией.
