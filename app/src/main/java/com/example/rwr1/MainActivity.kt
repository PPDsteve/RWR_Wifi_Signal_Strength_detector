package com.example.rwr1

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {

    // --- 常量 ---
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 123
        private const val SIGNAL_UPDATE_INTERVAL_MS = 1000L // 信号更新间隔 (毫秒)
        private const val BLINK_INTERVAL_MS = 500L      // 闪烁间隔 (毫秒)
        private const val RWR_START_DELAY_MS = 1000L    // 按下按钮后开始检测信号的延迟 (毫秒)
        private const val TOTAL_SOUNDS_TO_LOAD = 5      // 需要加载的声音总数 (start, close, low, mid, high)
    }

    // --- 视图控件 ---
    // 假设 RWRView 是你的自定义视图，你需要确保它已正确实现并存在
    private lateinit var rwrDisplay: RWRView
    private lateinit var signalLevelTextView: TextView
    private lateinit var missileAlertTextView: TextView
    private lateinit var toggleRwrButton: Button // 已从 yButton 重命名

    // --- 系统服务 ---
    private lateinit var wifiManager: WifiManager

    // --- 声音相关 ---
    private lateinit var soundPool: SoundPool
    private var rwrStartSoundId: Int = 0
    private var rwrCloseSoundId: Int = 0
    private var lowSoundId: Int = 0
    private var midSoundId: Int = 0
    private var highSoundId: Int = 0
    private var soundsLoadedCount = 0 // 已加载声音计数
    private var allSoundsLoaded = false // 是否所有声音都已加载完成

    // --- 状态变量 ---
    private var isRwrEnabled = false    // RWR 是否启用
    private var isCircleVisible = false // RWRView 中的圆圈是否可见 (用于闪烁)
    private var hasSignal = false       // 是否检测到有效的 Wi-Fi 信号 (已连接且 SSID 可知)

    // --- 线程与任务 ---
    private val handler = Handler(Looper.getMainLooper()) // 主线程 Handler

    // --- 定时任务 Runnable ---
    // 更新 Wi-Fi 信号信息的任务
    private val updateSignalRunnable = object : Runnable {
        override fun run() {
            // 仅在 RWR 启用时执行
            if (isRwrEnabled) {
                updateWifiSignalInfo()
                // 安排下一次执行
                handler.postDelayed(this, SIGNAL_UPDATE_INTERVAL_MS)
            }
            // 如果 isRwrEnabled 为 false，则不执行也不再安排下次任务
        }
    }

    // 控制闪烁动画的任务
    private val blinkRunnable = object : Runnable {
        override fun run() {
            // 仅在 RWR 启用且有信号时闪烁
            if (isRwrEnabled && hasSignal) {
                isCircleVisible = !isCircleVisible
                rwrDisplay.setCircleVisibility(isCircleVisible)
                missileAlertTextView.visibility = if (isCircleVisible) View.VISIBLE else View.INVISIBLE
                // 安排下一次闪烁
                handler.postDelayed(this, BLINK_INTERVAL_MS)
            } else {
                // 确保在条件不满足时，闪烁停止且元素隐藏
                // 这是保险措施，主要由 stopBlinkingAnimation() 处理显式停止
                isCircleVisible = false
                // 检查视图是否已初始化，避免潜在的 NPE
                if(::rwrDisplay.isInitialized) rwrDisplay.setCircleVisibility(false)
                if(::missileAlertTextView.isInitialized) missileAlertTextView.visibility = View.INVISIBLE
            }
        }
    }

    // --- Activity 生命周期回调 ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 使用真实的 R.layout.activity_main
        setContentView(R.layout.activity_main)

        // --- 初始化视图控件 ---
        try {
            // 使用真实的 R.id 引用
            rwrDisplay = findViewById(R.id.rwr_display)
            signalLevelTextView = findViewById(R.id.network_list)
            missileAlertTextView = findViewById(R.id.missile_alert_text)
            toggleRwrButton = findViewById(R.id.y_button) // 确保 activity_main.xml 中按钮的 ID 是 y_button
        } catch (e: NullPointerException) {
            // 处理在布局中找不到视图 ID 的情况
            android.util.Log.e("MainActivity", "查找视图时出错，请检查布局文件和 ID。", e)
            // 可以考虑给用户一个错误提示并结束 Activity
            finish() // 示例：如果 UI 损坏则退出
            return
        }

        toggleRwrButton.isEnabled = false // 初始禁用，直到声音加载完成且权限被授予

        // --- 初始化系统服务 ---
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // --- 初始化声音 ---
        setupSoundPool() // 配置 SoundPool
        loadSounds()     // 开始加载声音文件

        // --- 设置监听器 ---
        toggleRwrButton.setOnClickListener {
            toggleRwrState() // 切换 RWR 状态
        }

        // --- 初始权限检查 ---
        // 在视图、声音等基础设置完成后进行
        checkLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        // 恢复之前暂停的声音流 (如果 SoundPool 已初始化)
        if (::soundPool.isInitialized) {
            soundPool.autoResume()
        }

        // 仅当 RWR 之前是启用状态且权限仍然有效时，才重启更新和闪烁
        if (isRwrEnabled && hasLocationPermission()) {
            android.util.Log.d("MainActivity", "恢复 RWR 操作。")
            startSignalUpdates() // 会安排第一次信号更新
            // 可选：是否立即重新检查信号？updateSignalRunnable 很快会检查。
            // updateWifiSignalInfo() // 可选：强制在 resume 时立即检查一次
            // 如果需要，立即开始闪烁
            if (hasSignal) {
                startBlinkingAnimation()
            }
        } else if (isRwrEnabled && !hasLocationPermission()) {
            // 如果 RWR 是开启的，但在后台时权限被撤销了，强制关闭 RWR
            android.util.Log.w("MainActivity", "在后台时权限被撤销。正在禁用 RWR。")
            updateUiForPermissionDenied() // 更新 UI 显示权限问题
            isRwrEnabled = false // 先更新状态
            stopSignalUpdates()     // 停止后台任务
            stopBlinkingAnimation() // 停止动画并更新相关 UI
            // 可选：播放关闭声音或进一步更新 UI
            // 使用真实的 R.string 引用
            signalLevelTextView.text = getString(R.string.rwr_disabled_permission)
            toggleRwrButton.isEnabled = false // 确保按钮状态一致
        }
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("MainActivity", "暂停 RWR 操作。")
        // 暂停活动的声音流 (如果 SoundPool 已初始化)
        if (::soundPool.isInitialized) {
            soundPool.autoPause()
        }
        stopSignalUpdates()     // 停止周期性信号更新
        stopBlinkingAnimation() // 停止闪烁动画
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("MainActivity", "销毁 Activity。正在释放资源。")
        // 释放 SoundPool 资源 (如果已初始化)
        if (::soundPool.isInitialized) {
            soundPool.release()
        }
        // 移除 Handler 中所有待处理的回调和消息，防止内存泄漏
        handler.removeCallbacksAndMessages(null)
    }

    // --- 选项菜单 ---
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // 使用真实的 R.menu 引用
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // 使用真实的 R.id 引用
        return when (item.itemId) {
            R.id.action_about -> { // 确保 main_menu.xml 中关于菜单项的 ID 是 action_about
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- 权限处理 ---
    // 检查是否已获得精确位置权限
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 检查并请求位置权限
    private fun checkLocationPermission() {
        if (!hasLocationPermission()) {
            // 权限未授予，请求权限
            android.util.Log.i("MainActivity", "位置权限未授予。正在请求...")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            // 按钮保持禁用，直到权限结果返回
            // 使用真实的 R.string 引用
            signalLevelTextView.text = getString(R.string.permission_prompt)
        } else {
            // 权限已授予
            android.util.Log.i("MainActivity", "位置权限已被授予。")
            // 仅当声音也加载完成后才启用按钮
            if (allSoundsLoaded) {
                toggleRwrButton.isEnabled = true
                // 使用真实的 R.string 引用
                signalLevelTextView.text = getString(R.string.rwr_ready_prompt)
            } else {
                // 使用真实的 R.string 引用
                signalLevelTextView.text = getString(R.string.loading_sounds)
                // 按钮保持禁用，直到声音加载完成
            }
        }
    }

    // 处理权限请求结果的回调
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已被用户授予
                android.util.Log.i("MainActivity", "用户已授予位置权限。")
                // 仅当声音也加载完成后才启用按钮
                if (allSoundsLoaded) {
                    toggleRwrButton.isEnabled = true
                    // 使用真实的 R.string 引用
                    signalLevelTextView.text = getString(R.string.rwr_ready_prompt)
                } else {
                    // 使用真实的 R.string 引用
                    signalLevelTextView.text = getString(R.string.loading_sounds)
                    // 按钮保持禁用，直到声音加载完成
                }
            } else {
                // 权限被用户拒绝
                android.util.Log.w("MainActivity", "用户已拒绝位置权限。")
                updateUiForPermissionDenied() // 更新 UI 显示权限被拒绝
                toggleRwrButton.isEnabled = false // 保持按钮禁用
            }
        }
    }

    // --- 声音处理 ---
    // 配置 SoundPool
    private fun setupSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME) // 适合游戏或模拟器音效
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION) // 声音用于传达信息
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2) // 允许同时播放的最大声音流数量 (例如，允许关闭音效和信号音效短暂重叠)
            .setAudioAttributes(audioAttributes)
            .build()

        // 设置声音加载完成监听器
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                // 声音加载成功
                soundsLoadedCount++
                android.util.Log.d("MainActivity", "声音加载成功: ID $sampleId ($soundsLoadedCount/$TOTAL_SOUNDS_TO_LOAD)")
                if (soundsLoadedCount == TOTAL_SOUNDS_TO_LOAD) {
                    // 所有声音都已加载完成
                    allSoundsLoaded = true
                    android.util.Log.i("MainActivity", "所有声音已成功加载。")
                    // 声音就绪。仅当权限也被授予时才启用按钮。
                    if (hasLocationPermission()) {
                        toggleRwrButton.isEnabled = true
                        // 仅当 TextView 没有显示权限拒绝信息时才更新为 "Ready"
                        // 使用真实的 R.string 引用
                        if (signalLevelTextView.text.toString() != getString(R.string.permission_denied_required)) {
                            signalLevelTextView.text = getString(R.string.rwr_ready_prompt)
                        }
                    } else {
                        // 声音加载完成，但权限仍未授予或被拒绝
                        android.util.Log.w("MainActivity", "声音已加载，但权限被拒绝/待定。")
                        // 保持按钮禁用，UI 应反映权限状态
                    }
                }
            } else {
                // 声音加载失败
                android.util.Log.e("MainActivity", "加载声音 ID $sampleId 失败，状态码 $status")
                // 使用真实的 R.string 引用
                signalLevelTextView.text = getString(R.string.sound_load_error)
                toggleRwrButton.isEnabled = false // 如果声音加载失败，禁用 RWR 功能
                // 可以考虑显示一个更持久的错误信息
            }
        }
    }

    // 开始加载所有声音文件
    private fun loadSounds() {
        android.util.Log.d("MainActivity", "开始加载 $TOTAL_SOUNDS_TO_LOAD 个声音...")
        // 确保原始音频文件 (例如 rwr_start.ogg) 存在于 res/raw 目录下
        try {
            // 使用真实的 R.raw 引用
            rwrStartSoundId = soundPool.load(this, R.raw.rwr_start, 1)
            rwrCloseSoundId = soundPool.load(this, R.raw.rwr_close, 1)
            lowSoundId = soundPool.load(this, R.raw.rwr_low, 1)
            midSoundId = soundPool.load(this, R.raw.rwr_mid, 1)
            highSoundId = soundPool.load(this, R.raw.rwr_high, 1)
        } catch (e: Exception) {
            // 通常是资源未找到异常 (Resources$NotFoundException)
            android.util.Log.e("MainActivity", "查找声音资源时出错。请检查 res/raw 文件夹。", e)
            // 使用真实的 R.string 引用
            signalLevelTextView.text = getString(R.string.sound_resource_error)
            toggleRwrButton.isEnabled = false
            allSoundsLoaded = false // 标记声音加载失败
        }
    }

    // 播放指定 ID 的声音
    private fun playSound(soundId: Int) {
        // 只有当 soundId 有效、所有声音已加载、且 SoundPool 已初始化时才播放
        if (soundId != 0 && allSoundsLoaded && ::soundPool.isInitialized) {
            soundPool.play(soundId, 1f, 1f, 0, 0, 1f) // 左音量, 右音量, 优先级, 循环次数(0=不循环), 播放速率
        } else if (soundId != 0 && !allSoundsLoaded) {
            android.util.Log.w("MainActivity", "尝试在所有声音加载完成前播放声音 ID $soundId。")
        } else if (soundId != 0 && !::soundPool.isInitialized) {
            android.util.Log.e("MainActivity", "尝试播放声音 ID $soundId 但 SoundPool 未初始化。")
        }
    }

    // --- RWR 核心逻辑 ---
    // 切换 RWR 的启用/禁用状态
    private fun toggleRwrState() {
        isRwrEnabled = !isRwrEnabled // 翻转状态
        android.util.Log.i("MainActivity", "RWR 状态切换。当前已启用: $isRwrEnabled")

        if (isRwrEnabled) {
            // --- 启用 RWR ---
            playSound(rwrStartSoundId) // 播放启动音效
            // 立即清除之前的状态显示
            // 使用真实的 R.string 引用
            signalLevelTextView.text = getString(R.string.rwr_activating)
            missileAlertTextView.visibility = View.INVISIBLE // 确保告警初始隐藏
            // 检查视图是否已初始化，避免潜在的 NPE
            if(::rwrDisplay.isInitialized) rwrDisplay.setCircleVisibility(false) // 确保圆圈初始隐藏
            hasSignal = false                            // 重置信号检测状态

            // 延迟一小段时间后开始信号更新，给启动音效播放时间
            // updateSignalRunnable 会负责首次信号检查
            handler.postDelayed({
                // 再次检查状态，因为用户可能在延迟期间快速再次点击按钮
                if (isRwrEnabled) {
                    android.util.Log.d("MainActivity", "延迟后开始信号更新。")
                    startSignalUpdates()
                } else {
                    android.util.Log.d("MainActivity", "在信号更新开始前 RWR 已被禁用。")
                }
            }, RWR_START_DELAY_MS)

        } else {
            // --- 禁用 RWR ---
            playSound(rwrCloseSoundId) // 播放关闭音效
            stopSignalUpdates()       // 停止检查信号
            stopBlinkingAnimation()   // 立即停止闪烁并隐藏相关 UI
            // 使用真实的 R.string 引用
            signalLevelTextView.text = getString(R.string.rwr_disabled)
            hasSignal = false           // 重置信号状态
            // missileAlertTextView 和 rwrDisplay 的可见性由 stopBlinkingAnimation() 处理
        }
    }

    // --- 更新与动画控制 ---
    // 开始周期性信号更新
    private fun startSignalUpdates() {
        // 移除任何已存在的 updateSignalRunnable 回调，防止重复执行
        handler.removeCallbacks(updateSignalRunnable)
        // 立即发布任务。Runnable 内部会在 RWR 启用时自我重新安排。
        handler.post(updateSignalRunnable)
        android.util.Log.v("MainActivity", "已发布 updateSignalRunnable 任务")
    }

    // 停止周期性信号更新
    private fun stopSignalUpdates() {
        handler.removeCallbacks(updateSignalRunnable)
        android.util.Log.v("MainActivity", "已移除 updateSignalRunnable 回调")
    }

    // 开始闪烁动画
    private fun startBlinkingAnimation() {
        // 仅当 RWR 已启用且动画当前未运行时才启动
        if (isRwrEnabled && !handler.hasCallbacks(blinkRunnable)) {
            android.util.Log.v("MainActivity", "开始闪烁动画。")
            // 立即发布任务。Runnable 内部会检查 isRwrEnabled 和 hasSignal，并自我重新安排。
            handler.post(blinkRunnable)
        } else {
            android.util.Log.v("MainActivity", "闪烁动画未启动 (已在运行或 RWR 已禁用)。")
        }
    }

    // 停止闪烁动画并重置相关 UI
    private fun stopBlinkingAnimation() {
        handler.removeCallbacks(blinkRunnable)
        android.util.Log.v("MainActivity", "停止闪烁动画。")
        // 显式设置 UI 到非闪烁状态
        isCircleVisible = false
        // 检查视图是否已初始化，避免潜在的 NPE
        if(::rwrDisplay.isInitialized) rwrDisplay.setCircleVisibility(false)
        if(::missileAlertTextView.isInitialized) missileAlertTextView.visibility = View.INVISIBLE
    }

    // --- 核心 Wi-Fi 信息更新与处理 ---
    private fun updateWifiSignalInfo() {
        // 在执行任何操作前，再次确认权限状态
        if (!hasLocationPermission()) {
            android.util.Log.w("MainActivity", "updateWifiSignalInfo: 权限检查失败。")
            updateUiForPermissionDenied() // 更新 UI 显示权限问题
            // 如果 RWR 正在运行时丢失了权限，强制禁用它
            if (isRwrEnabled) {
                isRwrEnabled = false // 更新状态
                // 检查 SoundPool 是否已初始化，避免潜在的 NPE
                if(::soundPool.isInitialized) playSound(rwrCloseSoundId) // 可选：播放关闭音效
                stopSignalUpdates()      // 停止任务
                stopBlinkingAnimation()  // 更新 UI
                toggleRwrButton.isEnabled = false // 禁用按钮
            }
            return // 停止本次更新流程
        }

        // --- 获取 Wi-Fi 信息 (谨慎使用已弃用的方法) ---
        // 注意: WifiManager.connectionInfo 在 API 31+ 已被弃用。
        // 它需要 ACCESS_FINE_LOCATION 权限。
        // 它通常只能获取 *当前已连接* 网络的信息。
        val wifiInfo: WifiInfo? = try {
            wifiManager.connectionInfo
        } catch (e: SecurityException) {
            // 通常发生在 Manifest 中未声明权限或权限被动态撤销
            android.util.Log.e("MainActivity", "获取 WifiInfo 时发生 SecurityException。请检查 Manifest 权限。", e)
            updateUiForPermissionDenied() // 按权限问题处理
            // 可以考虑在此处禁用 RWR
            return // 停止本次更新
        } catch (e: Exception) {
            // 其他可能的异常
            android.util.Log.e("MainActivity", "获取 WifiInfo 时发生异常。", e)
            // 使用真实的 R.string 引用
            signalLevelTextView.text = getString(R.string.wifi_error)
            // 发生错误时认为没有信号
            hasSignal = false
            stopBlinkingAnimation()
            return // 停止本次更新
        }

        // --- 处理 Wi-Fi 信息 ---
        val currentSsid = wifiInfo?.ssid
        val currentRssi = wifiInfo?.rssi
        // 对于 RWR 目的，将 "<unknown ssid>" 或 null RSSI 视为无效信号
        val currentHasSignal = currentSsid != null && currentSsid != "<unknown ssid>" && currentRssi != null

        android.util.Log.v("MainActivity", "信号更新检查: SSID=$currentSsid, RSSI=$currentRssi, 是否有信号=$currentHasSignal")

        if (currentHasSignal) {
            // --- 检测到有效信号 ---
            // 清理 SSID 中可能包含的引号以便显示
            val cleanSsid = currentSsid!!.removePrefix("\"").removeSuffix("\"")
            // 使用真实的 R.string 引用，并传入格式化参数
            signalLevelTextView.text = getString(
                R.string.wifi_connected_format,
                cleanSsid,
                currentRssi!! // currentHasSignal 保证了此处非空
            )
            updateRWRFeedback(currentRssi) // 根据 RSSI 播放对应音效

            // 处理从“无信号”到“有信号”的转换
            if (!hasSignal) {
                android.util.Log.d("MainActivity", "信号已获取。")
                hasSignal = true
                // 开始闪烁 (startBlinkingAnimation 内部会检查 RWR 是否启用以及是否已在闪烁)
                startBlinkingAnimation()
            }
        } else {
            // --- 未检测到有效信号 (未连接或 SSID 未知) ---
            // 使用真实的 R.string 引用
            signalLevelTextView.text = getString(R.string.wifi_not_connected)

            // 处理从“有信号”到“无信号”的转换
            if (hasSignal) {
                android.util.Log.d("MainActivity", "信号已丢失。")
                hasSignal = false
                // 停止闪烁 (同时会隐藏相关 UI 元素)
                stopBlinkingAnimation()
            } else {
                // 如果本来就没有信号，确保闪烁动画确实是停止的（保险措施）
                stopBlinkingAnimation()
            }
        }
    }

    // --- 根据 RSSI 更新 RWR 反馈 (主要是声音) ---
    private fun updateRWRFeedback(rssi: Int) {
        // 音效播放依赖于 RWR 状态 (在 toggleRwrState 中管理)
        // 闪烁依赖于 RWR 状态 + hasSignal (在 blinkRunnable 和状态转换中管理)
        when {
            rssi >= -30 -> playSound(highSoundId) // 强信号
            rssi >= -50 -> playSound(midSoundId)  // 中等信号
            else -> playSound(lowSoundId)          // 弱信号
        }
    }

    // --- UI 辅助函数：更新为权限被拒绝状态 ---
    private fun updateUiForPermissionDenied() {
        // 使用真实的 R.string 引用
        signalLevelTextView.text = getString(R.string.permission_denied_required)
        // 可以根据需要禁用其他 UI 元素
    }
}