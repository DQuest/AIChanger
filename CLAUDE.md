# CLAUDE.md

AIChanger: удержание кнопки питания на vivo X200 Ultra (OriginOS 6, Android 16, без root) открывает Gemini вместо BlueLM.
Пользовательское описание и настройка: [README.md](README.md).

## Сборка и установка

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- vivo требует подтвердить установку по adb на экране: телефон должен быть разблокирован, иначе `INSTALL_FAILED_ABORTED: User rejected permissions`.
- Тестов нет, проверка только на устройстве (см. «Отладка»).
- Сборка только под `arm64-v8a` (нативные библиотеки conscrypt под другие ABI не нужны), minSdk 30, targetSdk 36.

## Архитектура

Два процесса из одного APK:

- **Приложение** (`dev.dquest.aichanger`): UI, сопряжение, запуск демона.
  - `MainActivity`: экран, сделан кодом, без XML.
  - `PairingService`: сопряжение с беспроводной отладкой, код вводится в уведомлении (RemoteInput).
  - `AdbManager`: ключ и сертификат adb-клиента на libadb-android, хранятся в Device Protected storage.
  - `DaemonLauncher`: включает беспроводную отладку (и при необходимости параметры разработчика), подключается к своему adbd,
    при первом запуске выдаёт себе `WRITE_SECURE_SETTINGS`, стартует демон через `app_process`, возвращает настройки как были.
  - `PowerKeyController`: настройки и состояние, `ensureRunning` запускает демон, если не отвечает демон текущей версии.
  - `BootReceiver` + `AutoStartService`: автозапуск после загрузки (до первой разблокировки) и после обновления.
  - `DaemonClient`: клиент протокола.
- **Демон** (`daemon/`, процесс `aichanger-daemon`, uid shell):
  - `PowerKeyDaemon`: точка входа `main`, TCP-сервер `127.0.0.1:47831` с токеном, следит за удалением приложения.
  - `PowerKeyEngine`: читает `/dev/input` (`pmic_pwrkey`), по удержанию шлёт `KEYCODE_VOICE_ASSIST`/`KEYCODE_ASSIST`, по ~2 с открывает меню питания.
  - `DaemonProtocol`: общие константы и команды (`ping`, `status`, `mode`, `stop`).

Правила, которые легко нарушить:

- **Меняете код демона — поднимайте `versionCode`** в `app/build.gradle.kts`. Приложение перезапускает демон, только если `ping` вернул другую версию.
- Код в `daemon/` работает вне приложения: там нет `Context`, Android API доступны только те, что работают из `app_process`
  с uid shell. Системные действия выполняются shell-командами (`sh(...)`).
- Всё, что нужно до первой разблокировки, должно быть `directBootAware` **на каждом компоненте** (флаг у `<application>`
  на компоненты не распространяется) и хранить данные в `createDeviceProtectedStorageContext()`.
- Команда запуска демона через libadb должна заканчиваться `& sleep 1`: `shell:` идёт через PTY, и без паузы фоновый
  процесс гибнет от hangup раньше, чем `setsid` его отвяжет. В конце потока libadb бросает «Stream closed» вместо -1.
- Демон переживает удаление приложения (uid shell), поэтому сам возвращает BlueLM и завершается (`watchForUninstall`).
  То же при команде `stop`.

## Особенности прошивки vivo

- `settings put` для нужных ключей молча не работает. Писать через
  `content insert --uri content://settings/<ns> --bind name:s:K --bind value:s:V`.
- BlueLM на кнопке выключается `system vivo_jovi_power_wakeup_switch=0`, включается `=1`. `global power_button_long_press` прошивка игнорирует.
- Экран блокировки vivo не пропускает панель Gemini (видна только подсветка): сначала `wm dismiss-keyguard`, затем ASSIST.
- Любая инжектированная клавиша во время удержания питания помечает нажатие обработанным, иначе отпускание гасит экран
  (на экране блокировки для этого шлётся `KEYCODE_WAKEUP`).
- Меню питания из shell: `input keyevent --longpress KEYCODE_POWER`.
- vivo отбрасывает загрузочные рассылки (`skip for auto start`), пока приложение не включено в
  `com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`. Поэтому `MainActivity.onStart` тоже запускает демон при необходимости.
- Номер `/dev/input/eventN` у `pmic_pwrkey` меняется между загрузками, устройство ищется по имени.
- Доверенные сети беспроводной отладки хранятся по BSSID. После загрузки подключение иногда не находит порт по mDNS,
  поэтому `DaemonLauncher` перезапускает беспроводную отладку перед повторными попытками.
- USB-отладка на этой прошивке продолжает работать и при выключенных параметрах разработчика.

## Отладка

```bash
adb shell pidof aichanger-daemon                       # демон жив?
adb shell ps -A -o PID,STIME,NAME | grep aichanger     # когда запущен
adb logcat -s AIChanger                                # логи приложения и демона
adb shell dumpsys activity activities | grep -m1 topResumedActivity   # открыт ли Gemini (FloatyActivity)
```

- При включённой беспроводной отладке adb на Mac видит второе устройство; выбирать USB через `ANDROID_SERIAL`.
- Откат BlueLM вручную: `adb shell content insert --uri content://settings/system --bind name:s:vivo_jovi_power_wakeup_switch --bind value:s:1`.
- `prototype/power_to_gemini.py` — первый прототип логики, работает с Mac по adb; `backup.txt` — исходные значения настроек.

## Стиль

- Интерфейс, комментарии и README на русском. Комментарии объясняют «почему», особенно обходы прошивки vivo.
- Сообщения коммитов на английском: заголовок в повелительном наклонении и тело с причиной.
- Версии зависимостей в `gradle/libs.versions.toml`; libadb-android берётся с JitPack (ограничено группой в `settings.gradle.kts`).
