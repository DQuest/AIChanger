# Имена не прячем: исходники открыты, а читаемые стектрейсы в logcat важнее экономии на именах.
# Заодно не ломаются имена, сохранённые в настройках (SetupStep).
-dontobfuscate

# Демон запускается через app_process по имени класса (DaemonLauncher.launchCommand): R8 об этом не знает.
-keep class dev.dquest.aichanger.daemon.PowerKeyDaemon {
    public static void main(java.lang.String[]);
}

# libspake2.so (сопряжение по коду) находит этот класс и его члены из нативного кода по имени.
# Остальная рефлексия libadb-android обращается к conscrypt, а его держат правила самой conscrypt.
-keep class io.github.muntashirakon.crypto.spake2.Spake2Context { *; }

# Адаптеры conscrypt для Android 4.x ссылаются на скрытые классы платформы; на Android 11+ они не используются.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
