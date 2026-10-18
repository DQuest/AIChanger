# Демон запускается через app_process по имени класса (DaemonLauncher.launchCommand): R8 об этом не знает.
-keep class dev.dquest.aichanger.daemon.PowerKeyDaemon {
    public static void main(java.lang.String[]);
}
