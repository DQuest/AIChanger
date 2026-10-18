# Имена не прячем: исходники открыты, а читаемые стектрейсы в logcat важнее экономии на именах.
# Заодно не ломаются имена, сохранённые в настройках (SetupStep).
-dontobfuscate

# Демон запускается через app_process по имени класса (DaemonLauncher.launchCommand): R8 об этом не знает.
-keep class dev.dquest.aichanger.daemon.PowerKeyDaemon {
    public static void main(java.lang.String[]);
}

# libadb-android (сопряжение и TLS к adbd) своих правил для R8 не приносит.
-keep class io.github.muntashirakon.** { *; }
