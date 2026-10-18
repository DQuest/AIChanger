# Имена не прячем: исходники открыты, а читаемые стектрейсы в logcat важнее экономии на именах.
# Заодно не ломаются имена, сохранённые в настройках (SetupStep).
-dontobfuscate

# Демон запускается через app_process по имени класса (DaemonLauncher.launchCommand): R8 об этом не знает.
-keep class dev.dquest.aichanger.daemon.PowerKeyDaemon {
    public static void main(java.lang.String[]);
}

# libadb-android (сопряжение и TLS к adbd) своих правил для R8 не приносит.
-keep class io.github.muntashirakon.** { *; }

# Адаптеры conscrypt для Android 4.x ссылаются на скрытые классы платформы; на Android 11+ они не используются.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
