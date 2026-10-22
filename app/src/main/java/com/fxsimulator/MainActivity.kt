package com.fxsimulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.fxsimulator.ui.GoldLabApp
import com.fxsimulator.ui.theme.GoldLabTheme
import com.fxsimulator.viewmodel.GameViewModel

/**
 * 唯一入口 Activity。
 *
 * 使用 activity 级 ViewModel：整个应用只有一个回放引擎与一个交易引擎，
 * 页面切换不会重建它们，也不会产生多个回放协程。
 *
 * AndroidManifest 中已声明 configChanges，屏幕旋转不会重建 Activity，
 * 因此在后台/旋转时都不会重复启动回放。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GoldLabTheme {
                GoldLabApp(viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 进入后台时暂停回放：价格和时间不再推进
        viewModel.onAppBackground()
    }
}
