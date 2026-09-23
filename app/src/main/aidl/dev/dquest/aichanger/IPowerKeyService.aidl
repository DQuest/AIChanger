package dev.dquest.aichanger;

interface IPowerKeyService {
    // Код транзакции, который Shizuku вызывает при удалении UserService.
    void destroy() = 16777114;

    void start(int holdMs) = 1;
    void stop() = 2;
    boolean isRunning() = 3;
    String status() = 4;
    void setBlueLmOnPowerKey(boolean enabled) = 5;
}
