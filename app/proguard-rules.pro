# CFD Simulator 混淆规则
#
# 当前 release 构建 isMinifyEnabled = false —— **故意不开启**。
# 混淆属于"之后再做"的优化项，等界面和玩法稳定下来再开，
# 否则它会让线上崩溃堆栈变得难以定位，反而拖慢排查。
#
# 开启前请先确认下面几条规则仍然正确：
#   -keep class com.fxsimulator.MainActivity { *; }
#   -keep class com.fxsimulator.GoldLabApplication { *; }

# 崩溃报告要能定位到行号，否则混淆后等于没有堆栈
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-renamesourcefileattribute SourceFile

# Activity / Application 由清单按名字引用
-keep class com.fxsimulator.MainActivity { *; }
-keep class com.fxsimulator.GoldLabApplication { *; }

# SQLiteOpenHelper 的子类由框架按名字实例化
-keep class * extends android.database.sqlite.SQLiteOpenHelper { *; }

# 枚举的 values() / valueOf() 在混淆后需要保留
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontwarn org.jetbrains.annotations.**
