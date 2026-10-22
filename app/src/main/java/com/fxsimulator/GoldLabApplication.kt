package com.fxsimulator

import android.app.Application
import com.fxsimulator.util.GoldLabLog

/**
 * 应用入口。必须是全进程最早执行的代码，
 * 因为崩溃处理器要在任何业务代码之前装好——否则启动阶段崩溃就抓不到。
 */
class GoldLabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GoldLabLog.init(this)
        GoldLabLog.i("App", "启动完成")
    }
}
