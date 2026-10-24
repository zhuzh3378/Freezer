# Shizuku：客户端 API 与服务端 AIDL 接口都靠 binder 通信，保留相关类。
# 命令走 moe.shizuku.server.IShizukuService#newProcess，剥掉 Stub 会让事务码错位。
-keep class moe.shizuku.server.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn moe.shizuku.server.**
-dontwarn rikka.shizuku.**
