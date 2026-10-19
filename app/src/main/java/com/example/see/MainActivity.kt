package com.example.see

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主界面：纯语音交互（面向视障用户，无任何按钮）。
 *
 * 打开后自动申请相机 + 录音权限，自动开始摄像头识别与讯飞连续语音监听，
 * 全部功能通过语音命令触发：找X / 停止识别 / 开始识别 / 保存画面 / 行走模式 / 退出。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView

    /** 顶部模式指示灯：绿=联网智能识别，灰=本地识别 */
    private lateinit var modeIndicator: android.view.View

    /** 语音识别封装 */
    private lateinit var voiceRecognizer: VoiceRecognizer

    /** TTS 是否正在播报（播报期间暂停聆听，避免听到自己的声音） */
    private var isSpeaking = false

    /** 是否已请求开启连续聆听（权限就绪后置 true） */
    private var listeningRequested = false

    /** 语音查找的目标（TFLite 英文标签），null 表示当前没有查找任务 */
    private var currentTarget: String? = null

    /** 语音查找目标的中文名（用于播报） */
    private var currentTargetName: String? = null

    /** 是否处于“盲道行走模式”：只提醒正前方障碍物，预警距离放大（默认开启） */
    private var walkingMode = true

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    /** ImageAnalysis 专用后台线程，避免阻塞主线程 */
    private var analysisExecutor: ExecutorService? = null

    /** TFLite 物体检测器：全局只创建一次并复用，不要每帧新建 */
    private lateinit var detector: Detector

    /** 帧间稳定化：类别投票缓冲，连续多帧一致才更新显示与播报 */
    private val stabilizer = FrameStabilizer()

    /** YUV → ARGB 复用的像素缓冲与位图，避免每帧分配大对象 */
    private var yuvPixels: IntArray? = null
    private var yuvBitmap: Bitmap? = null

    /** 当前摄像头是否正在预览 */
    private var isRunning = false

    /** 相机本次启动时间戳：启动后短暂宽限，避免刚打开时画面晃动/近距离误触发紧急播报 */
    private var cameraStartTimeMs = 0L

    /** 是否曾在 onPause 中暂停过：用于 onResume 精确恢复，避免启动时重复绑定相机 */
    private var pausedInBackground = false

    /** 网络是否可用（默认 false），联网后用于叠加智能识别 */
    private var networkAvailable = false

    /** 系统默认网络监听回调，onDestroy 时注销 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** ConnectivityManager，复用做注销 */
    private var connectivityManager: ConnectivityManager? = null

    /** 联网识别上一次返回的文本，用于“连续相同不重复”去重 */
    private var lastNetworkText: String? = null

    /** 上一次联网结果是否为“无障碍物”，用于连续静默 */
    private var lastNetworkWasClear = false

    /** 最近一次网络模式切换提示的时间，用于让其他播报让路，避免“已切换”被打断 */
    private var lastNetworkModeAnnounceMs = 0L

    /** 是否有一次联网请求正在执行，避免每 4 秒堆积 */
    @Volatile
    private var networkInFlight = false

    /** 联网请求连续失败次数：用于飞行模式/无外网时主动判定断网 */
    private var networkFailCount = 0

    /** 离线检测最近一次看到白名单障碍物的时间戳，用于避免“联网说无障碍”与离线结论矛盾 */
    private var lastLocalObstacleTimeMs = 0L

    /** 调试日志上次输出时间，用于按秒节流，避免逐帧刷 logcat */
    private var lastDebugMs = 0L

    /** 联网周期任务在主线程调度 */
    private val networkHandler = Handler(Looper.getMainLooper())

    /** 联网识别专用后台线程，避免阻塞相机分析线程 */
    private var networkExecutor: ExecutorService? = null

    /** TTS 语音播报引擎 */
    private var tts: TextToSpeech? = null

    /** 上一次播报的时间戳（毫秒），用于全局最小播报间隔 */
    private var lastBroadcastTime = 0L

    /** 每个类别的上次播报快照（key: 英文标签），用于同物体去重与变化判断 */
    private val lastBroadcastMap = HashMap<String, BroadcastRecord>()

    /** 一次播报的快照 */
    private class BroadcastRecord(
        val time: Long,
        val distance: Float,
        val direction: String,
        val centerX: Float,
        val centerY: Float
    )

    /** Android 9 及以下保存到相册需要的写存储权限 */
    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                saveCurrentFrame()
            } else {
                Toast.makeText(this, getString(R.string.toast_storage_needed), Toast.LENGTH_LONG)
                    .show()
                speak(getString(R.string.toast_storage_needed))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 使用期间保持屏幕常亮：视障用户边走边用，屏幕熄屏会中断体验且可能误触
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 强制深色主题，系统栏图标固定用浅色，避免在深色背景上看不清
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvStatus = findViewById(R.id.tv_status)
        modeIndicator = findViewById(R.id.mode_indicator)

        // 使用 TextureView 实现，保证 OverlayView 能正常叠在预览画面上方
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        // 创建一次检测器，后续所有帧复用同一个实例
        detector = Detector(this)

        analysisExecutor = Executors.newSingleThreadExecutor()

        // 初始化中文语音播报，仅在首次启动时播报欢迎语
        initTts(savedInstanceState == null)

        // 语音识别：初始化引擎（连续聆听在权限就绪后自动开启）
        voiceRecognizer = VoiceRecognizer.getInstance()
        voiceRecognizer.init(this)

        // 打开即用：自动申请权限 → 自动开启识别 + 自动聆听
        autoStart()

        // 自动联网识别：创建后台线程、监听网络状态、启动周期任务
        networkExecutor = Executors.newSingleThreadExecutor()
        registerNetworkCallback()
        networkHandler.postDelayed(networkRunnable, NETWORK_INTERVAL_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放检测器、线程池与语音引擎（语音监听在此停止）
        detector.close()
        stabilizer.reset()
        yuvBitmap?.recycle()
        yuvBitmap = null
        analysisExecutor?.shutdown()
        networkHandler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        networkExecutor?.shutdown()
        if (::voiceRecognizer.isInitialized) {
            voiceRecognizer.release()
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    override fun onPause() {
        super.onPause()
        pausedInBackground = true
        cancelSearchTimeout()
        // 退到后台：停止相机分析、语音监听与播报，避免后台耗电与误触发
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        if (::voiceRecognizer.isInitialized) {
            voiceRecognizer.stopListening()
        }
        tts?.stop()
        isRunning = false
    }

    override fun onResume() {
        super.onResume()
        // 首次启动由 onCreate/autoStart 负责，这里只在从后台返回时恢复
        if (!pausedInBackground) return
        pausedInBackground = false
        // 只做恢复，不再重复申请权限
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        }
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            ensureContinuousListening()
        }
    }

    /** 打开即用：已有权限则直接进入工作状态，否则一次性申请相机 + 录音权限 */
    private fun autoStart() {
        val missing = ArrayList<String>(2)
        if (!hasPermission(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA)
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO)
        if (missing.isEmpty()) {
            startCamera()
            ensureContinuousListening()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_START_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_START_PERMISSIONS) return

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.toast_camera_permission), Toast.LENGTH_LONG).show()
        }
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            ensureContinuousListening()
        } else {
            Toast.makeText(this, getString(R.string.toast_record_needed), Toast.LENGTH_LONG).show()
        }
    }

    /** 是否已获得指定权限 */
    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 开启连续聆听；若当前正在语音播报，则等播报结束由 TTS 回调再开启，
     * 避免把播报内容当成命令听到。
     */
    private fun ensureContinuousListening() {
        listeningRequested = true
        if (isSpeaking) return
        voiceRecognizer.startContinuousListening { text -> handleVoiceResult(text) }
    }

    /** 语音结果处理：先匹配控制类命令，再匹配“找X”检索命令 */
    private fun handleVoiceResult(text: String) {
        // 收到任何新命令都取消上一次找X的超时计时
        cancelSearchTimeout()

        // 1) 控制类命令（退出优先，避免被其它词包含）
        if (text.contains(CMD_EXIT)) {
            speak(getString(R.string.speak_exit))
            // 稍等播报结束再退出，保证用户能听到反馈
            window.decorView.postDelayed({ finish() }, EXIT_DELAY_MS)
            return
        }
        if (text.contains(CMD_SAVE)) {
            checkPermissionAndSave()
            return
        }
        if (text.contains(CMD_STOP)) {
            stopDetection()
            speak(getString(R.string.speak_detection_stopped))
            tvStatus.text = getString(R.string.status_last, getString(R.string.speak_detection_stopped))
            return
        }
        if (text.contains(CMD_START)) {
            startDetection()
            speak(getString(R.string.speak_detection_started))
            tvStatus.text = getString(R.string.status_last, getString(R.string.speak_detection_started))
            return
        }

        // 2) 找X：进入检索模式
        val matched = VOICE_KEYWORD_MAP.entries.firstOrNull { text.contains(it.key) }
        if (matched == null) {
            currentTarget = null
            currentTargetName = null
            speak(getString(R.string.speak_not_understood))
            tvStatus.text = getString(R.string.status_unrecognized, text)
            return
        }

        val name = displayName(matched.value)
        currentTarget = matched.value
        currentTargetName = name
        speak(getString(R.string.speak_searching, name))
        tvStatus.text = getString(R.string.status_searching, name)
        // 15 秒未找到则提醒并重新计时
        scheduleSearchTimeout()
    }

    /** 找X超时任务：超过 [SEARCH_TIMEOUT_MS] 仍未找到就播报提醒，并重新计时 */
    private val searchTimeoutRunnable: Runnable = Runnable {
        val name = currentTargetName ?: return@Runnable
        speak(getString(R.string.speak_search_timeout, name))
        tvStatus.text = getString(R.string.status_searching, name)
        // 继续等，直到找到物体或收到新命令
        window.decorView.postDelayed(searchTimeoutRunnable, SEARCH_TIMEOUT_MS)
    }

    /** 开始/重置找X超时计时 */
    private fun scheduleSearchTimeout() {
        window.decorView.removeCallbacks(searchTimeoutRunnable)
        window.decorView.postDelayed(searchTimeoutRunnable, SEARCH_TIMEOUT_MS)
    }

    /** 取消找X超时计时（找到物体、收到新命令或退到后台时调用） */
    private fun cancelSearchTimeout() {
        window.decorView.removeCallbacks(searchTimeoutRunnable)
    }

    /**
     * 注册默认网络监听：有网→networkAvailable=true（若刚从无网恢复，播报升级提示）；
     * 断网→networkAvailable=false（播报降级提示）。
     */
    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // 先同步初始化一次当前状态，避免启动时误播“网络可用”
        networkAvailable = connectivityManager?.activeNetwork != null
        updateModeIndicator()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (!networkAvailable) {
                        networkAvailable = true
                        resetNetworkDedup()
                        lastNetworkModeAnnounceMs = System.currentTimeMillis()
                        updateModeIndicator()
                        speak(getString(R.string.speak_network_up))
                    }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (networkAvailable) {
                        networkAvailable = false
                        resetNetworkDedup()
                        lastNetworkModeAnnounceMs = System.currentTimeMillis()
                        updateModeIndicator()
                        speak(getString(R.string.speak_network_down))
                    }
                }
            }
        }
        networkCallback = callback
        connectivityManager?.registerDefaultNetworkCallback(callback)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        networkCallback = null
        connectivityManager = null
    }

    /** 清空联网去重状态，网络切换后能立即播报一次 */
    private fun resetNetworkDedup() {
        lastNetworkText = null
        lastNetworkWasClear = false
    }

    /** 根据当前网络状态更新顶部模式指示灯：绿=联网智能识别，灰=本地识别 */
    private fun updateModeIndicator() {
        val color = if (networkAvailable) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
        modeIndicator.background.setTint(color)
    }

    /**
     * 联网描述周期任务：
     * - 有网时每 [NETWORK_INTERVAL_MS] 请求一次；
     * - 判定断网后，改为每 [NETWORK_PROBE_INTERVAL_MS] 探测一次，成功即自动恢复联网，
     *   避免“卡在断网状态无法恢复”（系统网络回调在弱网/飞行模式切换时可能漏报）。
     * 同一时刻只允许一个请求。
     */
    private val networkRunnable = object : Runnable {
        override fun run() {
            val interval = if (networkAvailable) NETWORK_INTERVAL_MS else NETWORK_PROBE_INTERVAL_MS
            networkHandler.postDelayed(this, interval)
            if (!isRunning || networkInFlight) return
            val bitmap = previewView.bitmap ?: return
            networkInFlight = true
            networkExecutor?.execute {
                val result = requestNetworkDescription(bitmap)
                networkInFlight = false
                runOnUiThread { handleNetworkRequestResult(result) }
            }
        }
    }

    /**
     * 联网请求结果统一处理：
     * 成功 → 确认网络可用（系统回调漏报时也能恢复）；
     * 连续失败 [NETWORK_FAIL_THRESHOLD] 次 → 主动判定断网（飞行模式/无外网时系统回调可能误报"网络可用"）。
     * 判定断网后不再停发请求，networkRunnable 会以探测间隔继续尝试，成功后自动恢复。
     */
    private fun handleNetworkRequestResult(result: String?) {
        if (result != null) {
            networkFailCount = 0
            if (!networkAvailable) {
                networkAvailable = true
                resetNetworkDedup()
                lastNetworkModeAnnounceMs = System.currentTimeMillis()
                updateModeIndicator()
                speak(getString(R.string.speak_network_up))
            }
            handleNetworkResult(result)
        } else {
            networkFailCount++
            if (networkFailCount >= NETWORK_FAIL_THRESHOLD && networkAvailable) {
                networkFailCount = 0
                networkAvailable = false
                resetNetworkDedup()
                lastNetworkModeAnnounceMs = System.currentTimeMillis()
                updateModeIndicator()
                speak(getString(R.string.speak_network_down))
            }
        }
    }

    /** 把帧缩放到长边不超过 maxEdge，减少上传数据量与大模型处理耗时（降低延迟） */
    private fun scaleBitmapForUpload(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        val targetW = (src.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    /** 把当前画面发送到多模态大模型，返回中文障碍物描述；失败/超时返回 null（不切模式、不播报） */
    private fun requestNetworkDescription(bitmap: Bitmap): String? {
        var conn: HttpURLConnection? = null
        return try {
            // 先缩放到小尺寸再压缩上传，显著降低延迟
            val scaled = scaleBitmapForUpload(bitmap, NETWORK_IMAGE_MAX_EDGE)
            val bytes = ByteArrayOutputStream().use { bos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                bos.toByteArray()
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            conn = (URL(NETWORK_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $NETWORK_API_KEY")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            conn.outputStream.use { it.write(buildNetworkBody(b64).toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode !in 200..299) return null
            val json = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parseNetworkResponse(json)
        } catch (e: Exception) {
            Log.i(TAG, "联网识别请求失败：${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 组装 chat/completions 请求体：图片 + 中文提示词 */
    private fun buildNetworkBody(b64: String): String {
        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
            .put(JSONObject().put("type", "text").put("text", NETWORK_PROMPT))
        val root = JSONObject()
            .put("model", NETWORK_MODEL)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", NETWORK_MAX_TOKENS)
        return root.toString()
    }

    /** 从 OpenAI 兼容响应里提取 content 文本 */
    private fun parseNetworkResponse(json: String): String? {
        return try {
            val choices = JSONObject(json).optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            choices.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.i(TAG, "联网识别解析失败：${e.message}")
            null
        }
    }

    /** 联网描述播报：连续相同静默；“无障碍物”单独播报一次并连续静默；语音与字幕同步输出 */
    private fun handleNetworkResult(result: String) {
        // 模式切换提示刚播报时让路，避免互相打断
        if (System.currentTimeMillis() - lastNetworkModeAnnounceMs < NETWORK_ANNOUNCE_GRACE_MS) return
        val isClear = result.contains("无障碍物") || result.contains("无障碍") || result.contains("没有障碍") ||
            result.contains("畅通") || result.contains("路面平整") || result.contains("路况良好") ||
            result.contains("没有障碍物")
        if (isClear) {
            // 保守处理：离线刚检测到白名单障碍物时，不播报“前方无障碍”，避免两分支结论矛盾
            val localJustSawObstacle =
                System.currentTimeMillis() - lastLocalObstacleTimeMs < LOCAL_OBSTACLE_RECENT_MS
            if (localJustSawObstacle) return
            if (!lastNetworkWasClear) {
                lastNetworkWasClear = true
                lastNetworkText = null
                val text = getString(R.string.speak_network_clear)
                speak(text)
                tvStatus.text = text
            }
            return
        }
        lastNetworkWasClear = false
        if (result == lastNetworkText) return
        lastNetworkText = result
        // 直接播报障碍物描述，不加“联网识别”前缀，更简洁、降低延迟感
        speak(result)
        tvStatus.text = result
    }

    /** 初始化 TTS，语言设置为中文；playWelcome 为 true 时播报一次启动欢迎语 */
    private fun initTts(playWelcome: Boolean) {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }

                    override fun onDone(utteranceId: String?) = onSpeechFinished()

                    override fun onError(utteranceId: String?) = onSpeechFinished()

                    override fun onStop(utteranceId: String?, interrupted: Boolean) =
                        onSpeechFinished()
                })
                val result = tts?.setLanguage(Locale.CHINA)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Toast.makeText(this, getString(R.string.toast_tts_unsupported), Toast.LENGTH_LONG)
                        .show()
                } else if (playWelcome) {
                    speak(getString(R.string.speak_welcome))
                }
            } else {
                Log.e(TAG, "TTS 初始化失败")
            }
        }
    }

    /** 播报结束（正常结束/出错/被打断）后恢复连续聆听 */
    private fun onSpeechFinished() {
        runOnUiThread {
            isSpeaking = false
            if (listeningRequested) {
                voiceRecognizer.startContinuousListening { text -> handleVoiceResult(text) }
            }
        }
    }

    /** 语音播报（需在主线程调用）；播报期间暂停聆听，避免听到自己的声音 */
    private fun speak(text: String) {
        val engine = tts ?: return
        voiceRecognizer.pauseListening()
        isSpeaking = true
        // 先停掉上一段未播完的内容，避免 TTS 排队堆积
        engine.stop()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** 保存画面前的权限检查：Android 10+ 走 MediaStore，无需写存储权限 */
    private fun checkPermissionAndSave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            saveCurrentFrame()
        } else {
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** 把当前画面（含检测框）保存到相册，并用语音反馈结果 */
    private fun saveCurrentFrame() {
        val bitmap = buildFrameBitmap()
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.toast_frame_not_ready), Toast.LENGTH_SHORT).show()
            speak(getString(R.string.toast_frame_not_ready))
            return
        }

        val name = "see_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + getString(R.string.album_folder)
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, getString(R.string.toast_save_failed), Toast.LENGTH_SHORT).show()
            speak(getString(R.string.toast_save_failed))
            return
        }

        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
            speak(getString(R.string.speak_saved))
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "保存画面失败", e)
            Toast.makeText(this, getString(R.string.toast_save_failed), Toast.LENGTH_SHORT).show()
            speak(getString(R.string.toast_save_failed))
        }
    }

    /** 把 PreviewView 的画面与 OverlayView 的检测框合成为一张位图 */
    private fun buildFrameBitmap(): Bitmap? {
        val previewBitmap = previewView.bitmap ?: return null
        if (overlayView.width == 0 || overlayView.height == 0) return null

        val result = previewBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(result)
        canvas.save()
        // OverlayView 与 PreviewView 尺寸一致，按比例缩放即可对齐检测框
        canvas.scale(
            result.width.toFloat() / overlayView.width,
            result.height.toFloat() / overlayView.height
        )
        overlayView.draw(canvas)
        canvas.restore()
        return result
    }

    /** 绑定 PreviewView、ImageAnalysis 与后置摄像头，启动实时预览 + 检测 */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            // 预览与分析共用同一个 4:3 分辨率策略，保证检测框坐标换算准确
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // 图像分析：只保留最新一帧，避免堆积
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor!!) { imageProxy ->
                detectFrame(imageProxy)
            }
            imageAnalysis = analysis

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.toast_camera_failed), Toast.LENGTH_LONG).show()
                return@addListener
            }

            isRunning = true
            cameraStartTimeMs = System.currentTimeMillis()
            // 相机就绪后恢复自动聆听（若播报中则等播报结束后再由 TTS 回调开启）
            ensureContinuousListening()
        }, ContextCompat.getMainExecutor(this))
    }

    /** 语音命令“开始识别”：重新绑定摄像头，恢复识别与自动播报 */
    private fun startDetection() {
        if (isRunning) return
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_START_PERMISSIONS
            )
        }
    }

    /** 语音命令“停止识别”：解绑摄像头并清空检测框，语音监听保持运行 */
    private fun stopDetection() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()

        isRunning = false
        currentTarget = null
        currentTargetName = null
        cancelSearchTimeout()
        stabilizer.reset()
        // 清空播报去重状态，重新开始识别时允许立即播报
        lastBroadcastMap.clear()
        lastBroadcastTime = 0L
        overlayView.clear()
        tvStatus.text = getString(R.string.status_none)
    }

    /**
     * 对单帧图像做物体检测：ImageProxy → Bitmap → 旋转摆正 → TFLite 推理 → 帧间稳定化。
     *
     * 注意：ImageProxy 必须在检测完成后 close()，否则会阻塞后续帧。
     */
    private fun detectFrame(imageProxy: ImageProxy) {
        var rotatedBitmap: Bitmap? = null
        try {
            // YUV_420_888 → ARGB（CameraX 1.3.0 没有 ImageProxy.toBitmap()）
            val sourceBitmap = yuvToBitmap(imageProxy)

            // 按 sensor 方向旋转摆正，保证框坐标与预览画面一致
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val uprightBitmap = if (rotationDegrees == 0) {
                sourceBitmap
            } else {
                // 90° 整数倍旋转无需滤镜，用 false 更快且无损
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(
                    sourceBitmap,
                    0,
                    0,
                    sourceBitmap.width,
                    sourceBitmap.height,
                    matrix,
                    false
                ).also { rotatedBitmap = it }
            }

            // 推理得到原始检测结果（坐标为旋转摆正后的像素坐标）
            val raw = detector.detect(uprightBitmap)

            // 帧间稳定化：类别投票 + 连续帧确认，抑制识别闪烁
            val stable = stabilizer.stabilize(raw, uprightBitmap.width)

            val frameWidth = uprightBitmap.width
            val frameHeight = uprightBitmap.height
            runOnUiThread { showDetections(stable, frameWidth, frameHeight) }
        } catch (e: Exception) {
            Log.e(TAG, "物体检测失败", e)
        } finally {
            // 仅回收旋转产生的临时位图；源位图是复用缓冲，不能回收
            rotatedBitmap?.recycle()
            imageProxy.close()
        }
    }

    /**
     * 把 YUV_420_888 的 ImageProxy 转成 ARGB_8888 位图。
     *
     * 复用内部像素数组与位图，避免每帧创建大对象；返回的位图下一帧会被覆盖，
     * 调用方不应持有或回收它。
     *
     * 性能优化：BT.601 转换用整数查表完成，去掉每像素 5 次浮点乘加。
     * 预生成 256 级亮度（Y）与 256 级色度（U/V）的乘积项，循环体内只做查表 + 加法 + 移位，
     * 在逐帧全分辨率场景下能显著降低分析线程 CPU 占用、提升帧率。
     */
    private fun yuvToBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var pixels = yuvPixels
        if (pixels == null || pixels.size < width * height) {
            pixels = IntArray(width * height)
            yuvPixels = pixels
        }

        // 预计算的整数查找表：把 BT.601 系数放大 2^10 后取整，避免浮点
        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            val uvRowStart = (row shr 1) * uvRowStride
            val outRowStart = row * width
            for (col in 0 until width) {
                val y = yBuffer.get(yRowStart + col * yPixelStride).toInt() and 0xFF
                val uvIndex = uvRowStart + (col shr 1) * uvPixelStride
                val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                // BT.601 整数近似：r = 1.164y + 1.596v，g = 1.164y - 0.813v - 0.391u，
                // b = 1.164y + 2.018u。系数放大 1024 倍取整，累加后右移 10 位恢复。
                val yf = if (y < 16) 0 else y - 16
                val r = (1192 * yf + 1634 * v + 512) shr 10
                val g = (1192 * yf - 833 * v - 400 * u + 512) shr 10
                val b = (1192 * yf + 2066 * u + 512) shr 10
                val rr = if (r < 0) 0 else if (r > 255) 255 else r
                val gg = if (g < 0) 0 else if (g > 255) 255 else g
                val bb = if (b < 0) 0 else if (b > 255) 255 else b
                pixels[outRowStart + col] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        var bitmap = yuvBitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            yuvBitmap = bitmap
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /** 在主线程把检测结果交给 OverlayView 绘制，并触发语音播报判断 */
    private fun showDetections(
        detections: List<Detector.Detection>,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        val overlayDetections = detections.map { toDetection(it, sourceWidth) }
        overlayView.setResults(overlayDetections, sourceWidth, sourceHeight)

        if (detections.isEmpty()) return

        val distances = detections.map { distanceOf(it) }

        // 行走模式：只提醒正前方障碍物，逻辑独立于普通环境播报
        if (walkingMode) {
            handleWalkingMode(detections, distances, sourceWidth, sourceHeight)
            return
        }

        // 1) 紧急优先：距离 < 0.5 米的障碍物中最近的那个
        val dangerIndex = detections.indices
            .filter { distances[it] > 0f && distances[it] < DANGER_DISTANCE_METERS }
            .minByOrNull { distances[it] }
        if (dangerIndex != null) {
            overlayView.setDangerIndex(dangerIndex)
            broadcastIfNeeded(
                detections[dangerIndex],
                directionOf(detections[dangerIndex].box.centerX(), sourceWidth),
                sourceWidth,
                sourceHeight
            )
            return
        }

        // 2) 语音查找：命中目标立刻播报并结束本次查找
        val target = currentTarget
        if (target != null) {
            val index = detections.indices.firstOrNull { detections[it].label == target }
            if (index != null) {
                val direction = directionOf(detections[index].box.centerX(), sourceWidth)
                val name = currentTargetName ?: displayName(target)
                val text = getString(
                    R.string.speak_found,
                    name,
                    direction,
                    formatMeters(distances[index])
                )
                speak(text)
                tvStatus.text = getString(R.string.status_found, text)
                currentTarget = null
                currentTargetName = null
                // 已找到，取消超时提醒
                cancelSearchTimeout()
            }
            return
        }

        // 3) 普通：取画面中最大的框（离用户最近/最明显）
        val mainIndex = detections.indices.maxByOrNull { i ->
            val b = detections[i].box
            b.width().toLong() * b.height().toLong()
        } ?: return
        broadcastIfNeeded(
            detections[mainIndex],
            directionOf(detections[mainIndex].box.centerX(), sourceWidth),
            sourceWidth,
            sourceHeight
        )
    }

    /** 估算某个检测结果的距离（米），无法估算返回 -1 */
    private fun distanceOf(detection: Detector.Detection): Float =
        DistanceEstimator.estimate(detection.label, detection.box.width())

    /**
     * 行走模式播报判断：只关注画面中间 1/3、类别在白名单内、距离 < [WALK_DANGER_DISTANCE_METERS] 的最近障碍物。
     * - 距离 < [WALK_URGENT_DISTANCE_METERS] 为紧急提醒，每 [WALK_URGENT_COOLDOWN_MS] 可重复一次；
     * - 其余为普通预警，同一物体 [WALK_COOLDOWN_MS] 内不重复；
     * - 白名单外的类别（鸟/球/食物等）、两侧物体、前方无障碍物时均不播报。
     */
    private fun handleWalkingMode(
        detections: List<Detector.Detection>,
        distances: List<Float>,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val frameArea = frameWidth.toFloat() * frameHeight.toFloat()
        // 每帧候选 dump（节流）：label、距离、中心X占比、框面积占比，便于对齐各过滤条件
        val sb = StringBuilder("帧 ${frameWidth}x${frameHeight} ${detections.size} 个目标")
        detections.forEachIndexed { i, d ->
            val cxr = String.format(Locale.CHINA, "%.2f", d.box.centerX() / frameWidth)
            val ar = String.format(Locale.CHINA, "%.2f", d.box.width() * d.box.height() / frameArea)
            val dist = if (distances[i] > 0f) String.format(Locale.CHINA, "%.1f", distances[i]) else "?"
            sb.append(" [").append(d.label).append(" d=").append(dist)
                .append(" bw=").append(d.box.width().toInt())
                .append(" cxr=").append(cxr).append(" ar=").append(ar).append("]")
        }
        dbg(sb.toString())

        // 启动宽限：刚打开相机时画面晃动且常被身体/桌面等近距离目标占满，短暂静默
        if (System.currentTimeMillis() - cameraStartTimeMs < WALK_START_GRACE_MS) {
            dbg("静默：启动宽限期内")
            return
        }

        // 正前方（画面中间 1/3）、非贴脸大框的障碍物：
        // 白名单类别 2 米内预警；非白名单类别（杯子等/护栏误检）仅在 1 米内才报，避免远处小物误报
        val aheadCandidates = detections.indices.filter { i ->
            val box = detections[i].box
            val centerX = box.centerX()
            val boxArea = box.width() * box.height()
            val inWhitelist = detections[i].label in WALK_PRIORITY_LABELS
            val inRange = distances[i] > 0f && (
                (inWhitelist && distances[i] < WALK_DANGER_DISTANCE_METERS) ||
                    distances[i] < WALK_URGENT_DISTANCE_METERS
                )
            centerX in frameWidth * WALK_AHEAD_LEFT_RATIO..frameWidth * WALK_AHEAD_RIGHT_RATIO &&
                inRange &&
                boxArea < frameArea * WALK_MAX_BOX_AREA_RATIO
        }
        val aheadIndex = aheadCandidates.minByOrNull { distances[it] }
        if (aheadIndex == null) {
            dbg("静默：无候选（对齐上方 dump：d 白名单<2 或非白名单<1；cxr 0.33~0.67；ar<${WALK_MAX_BOX_AREA_RATIO}）")
            return
        }

        val detection = detections[aheadIndex]
        val distance = distances[aheadIndex]
        val englishLabel = detection.label
        // 白名单内的类别报具体名称；白名单外的类别（垃圾桶被误判成“马桶”等）统一按“障碍物”播报，避免报错名称误导用户
        val name = if (englishLabel in WALK_PRIORITY_LABELS) displayName(englishLabel) else getString(R.string.obstacle_generic)
        val centerX = detection.box.centerX()
        val centerY = detection.box.centerY()
        val direction = getString(R.string.dir_ahead)

        // 记录“离线刚看到障碍物”，供联网“无障碍”结论做保守判断，避免矛盾
        lastLocalObstacleTimeMs = System.currentTimeMillis()

        // 高亮前方最近障碍物
        overlayView.setDangerIndex(aheadIndex)

        val now = System.currentTimeMillis()
        val record = lastBroadcastMap[englishLabel]

        // 真正危险：距离 < 0.5 米才用“小心”急促提醒，5 秒内可重复（任何模式都保留）
        if (distance < WALK_CRITICAL_DISTANCE_METERS) {
            if (record != null && now - record.time < WALK_URGENT_COOLDOWN_MS) {
                dbg("静默：紧急冷却中（${now - record.time}ms < $WALK_URGENT_COOLDOWN_MS）")
                return
            }
            dbg("播报：紧急 critical d=${formatMeters(distance)} $englishLabel")
            doBroadcast(
                getString(R.string.speak_walk_urgent, formatMeters(distance), name),
                englishLabel, now, distance, direction, centerX, centerY
            )
            tvStatus.text = getString(R.string.status_walk_alert, formatMeters(distance), name)
            return
        }

        // 模式切换提示刚播报时，普通预警让路 1.5 秒，避免把“已切换本地识别”打断
        if (now - lastNetworkModeAnnounceMs < NETWORK_ANNOUNCE_GRACE_MS) {
            dbg("静默：网络切换提示让路期内")
            return
        }

        // 联网时：白名单高优先级类别（人/车/自行车等）仍由离线直接报位置，不受大模型影响；
        // 大模型负责补充 COCO 没有的类别（杆子/护栏/锥桶等），不再压掉离线检测结果。
        // 这样避免“离线已检测到自行车，联网却说前方无障碍”的矛盾。

        // 普通预警：直接报位置（“前方X米，有Y，请注意绕行”），同一物体 5 秒内不重复
        if (record != null && now - record.time < WALK_COOLDOWN_MS) {
            dbg("静默：同物体冷却中（${now - record.time}ms < $WALK_COOLDOWN_MS）")
            return
        }
        dbg("播报：普通预警 d=${formatMeters(distance)} $englishLabel")
        doBroadcast(
            getString(R.string.speak_walk_warn, formatMeters(distance), name),
            englishLabel, now, distance, direction, centerX, centerY
        )
        tvStatus.text = getString(R.string.status_walk_alert, formatMeters(distance), name)
    }

    /** 根据检测框中心横坐标判断方位（按画面宽度五等分，便于视障用户精确感知位置） */
    private fun directionOf(centerX: Float, frameWidth: Int): String = when {
        centerX < frameWidth * 0.2f -> getString(R.string.dir_left)
        centerX < frameWidth * 0.4f -> getString(R.string.dir_slightly_left)
        centerX <= frameWidth * 0.6f -> getString(R.string.dir_ahead)
        centerX <= frameWidth * 0.8f -> getString(R.string.dir_slightly_right)
        else -> getString(R.string.dir_right)
    }

    /** 距离格式化：保留 1 位小数 */
    private fun formatMeters(distance: Float): String =
        String.format(Locale.CHINA, "%.1f", distance)

    /** 把 TFLite 的单个检测结果转成 OverlayView 需要的结构（含距离与方位） */
    private fun toDetection(detection: Detector.Detection, frameWidth: Int): OverlayView.Detection {
        val distance = DistanceEstimator.estimate(detection.label, detection.box.width())

        return OverlayView.Detection(
            box = RectF(detection.box),
            label = if (detection.label in WALK_PRIORITY_LABELS) displayName(detection.label)
            else getString(R.string.obstacle_generic),
            distanceMeters = distance,
            direction = directionOf(detection.box.centerX(), frameWidth)
        )
    }

    /**
     * 播报判断（防止重复刷屏）：
     * - 全局最小间隔 [MIN_BROADCAST_INTERVAL_MS]；紧急（距离 < 0.5 米）预警可突破，但仍有 2 秒最小间隔；
     * - 同一物体（类别相同且框中心偏移 < 15%）[SAME_OBJECT_WINDOW_MS] 内不重复播报；
     * - 只有类别变化、距离变化超过 [DISTANCE_CHANGE_METERS]、或方位变化时才重新播报。
     */
    private fun broadcastIfNeeded(
        detection: Detector.Detection,
        direction: String,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val englishLabel = detection.label
        val name = displayName(englishLabel)
        val distance = DistanceEstimator.estimate(englishLabel, detection.box.width())
        if (distance <= 0f) return

        val now = System.currentTimeMillis()
        val centerX = detection.box.centerX()
        val centerY = detection.box.centerY()
        val record = lastBroadcastMap[englishLabel]

        // 紧急预警：突破普通冷却，但保留 2 秒最小间隔，避免连续刷屏
        if (distance < DANGER_DISTANCE_METERS) {
            if (record != null && now - record.time < DANGER_COOLDOWN_MS) return
            doBroadcast(
                getString(R.string.speak_danger, formatMeters(distance)),
                englishLabel, now, distance, direction, centerX, centerY
            )
            return
        }

        // 全局最小播报间隔：任意两次播报至少间隔 MIN_BROADCAST_INTERVAL_MS
        if (now - lastBroadcastTime < MIN_BROADCAST_INTERVAL_MS) return

        if (record != null) {
            val sameObject = abs(centerX - record.centerX) < frameWidth * OBJECT_MATCH_RATIO &&
                abs(centerY - record.centerY) < frameHeight * OBJECT_MATCH_RATIO
            val distanceChanged = abs(distance - record.distance) > DISTANCE_CHANGE_METERS
            val directionChanged = direction != record.direction
            val inSameObjectWindow = now - record.time < SAME_OBJECT_WINDOW_MS

            // 同一物体、窗口内、距离变化不超过阈值 → 不重复播报
            if (sameObject && inSameObjectWindow && !distanceChanged) return
            // 距离与方位都没明显变化 → 窗口内不重复播报
            if (!distanceChanged && !directionChanged && inSameObjectWindow) return
        }

        doBroadcast(
            getString(R.string.speak_found, name, direction, formatMeters(distance)),
            englishLabel, now, distance, direction, centerX, centerY
        )
    }

    /** 执行一次播报并记录快照，供后续去重判断 */
    private fun doBroadcast(
        text: String,
        label: String,
        time: Long,
        distance: Float,
        direction: String,
        centerX: Float,
        centerY: Float
    ) {
        speak(text)
        lastBroadcastTime = time
        lastBroadcastMap[label] = BroadcastRecord(time, distance, direction, centerX, centerY)
        tvStatus.text = getString(R.string.status_last, text)
    }

    /** 把英文标签转成中文显示名称 */
    private fun displayName(label: String): String = OverlayView.toChinese(label)

    /** 节流调试日志：最多每秒向 logcat 输出一条，便于定位“有框但不播报”卡在哪个过滤条件；不占用屏幕显示 */
    private fun dbg(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastDebugMs < 1000L) return
        lastDebugMs = now
        Log.d(DBG, msg)
    }

    private companion object {
        const val TAG = "MainActivity"

        /** 播报链路调试日志专用 tag，logcat 过滤用：adb logcat SeeDebug:D */
        const val DBG = "SeeDebug"
        const val UTTERANCE_ID = "utteranceId"

        /** 权限请求码：相机 + 录音 */
        const val REQUEST_START_PERMISSIONS = 100

        /** 说完“退出”后延迟关闭的时间，保证用户能听到反馈 */
        const val EXIT_DELAY_MS = 1500L

        /** 找X超时提醒间隔：超过该时长仍未找到就提醒一次 */
        const val SEARCH_TIMEOUT_MS = 15000L

        /** 全局最小播报间隔：任意两次播报至少间隔该时长 */
        const val MIN_BROADCAST_INTERVAL_MS = 5000L

        /** 同一物体去重窗口：窗口内且距离无明显变化则不重复播报 */
        const val SAME_OBJECT_WINDOW_MS = 10000L

        /** 距离变化阈值（米）：超过该值才认为物体距离有明显变化 */
        const val DISTANCE_CHANGE_METERS = 1.5f

        /** 判定“同一物体”的框中心偏移比例（相对画面宽/高） */
        const val OBJECT_MATCH_RATIO = 0.15f

        /** 紧急障碍物距离阈值（米）：小于该值触发预警 */
        const val DANGER_DISTANCE_METERS = 0.5f

        /** 紧急预警冷却时间：2 秒内可再次播报 */
        const val DANGER_COOLDOWN_MS = 2000L

        /** 行走模式预警距离（米）：仅提醒该距离以内的前方障碍物 */
        const val WALK_DANGER_DISTANCE_METERS = 2.0f

        /** 行走模式近距阈值（米）：非白名单类别只在该距离内播报；联网时该距离内仍直接报位置 */
        const val WALK_URGENT_DISTANCE_METERS = 1.0f

        /** 真正危险距离（米）：只有该距离内才用“小心”急促提醒 */
        const val WALK_CRITICAL_DISTANCE_METERS = 0.5f

        /** 行走模式普通预警冷却：同一物体在该时长内不重复播报 */
        const val WALK_COOLDOWN_MS = 5000L

        /** 行走模式紧急提醒冷却：该时长可重复提醒一次 */
        const val WALK_URGENT_COOLDOWN_MS = 5000L

        /** 行走模式关注区域：画面中间 1/3 的左右边界比例 */
        const val WALK_AHEAD_LEFT_RATIO = 1f / 3f
        const val WALK_AHEAD_RIGHT_RATIO = 2f / 3f

        /** 相机启动后宽限时间（毫秒）：该时段内不播报行走预警，避免开屏晃动误触发 */
        const val WALK_START_GRACE_MS = 3000L

        /** 检测框面积占画面比例上限：超过该值视为贴脸/自己身体/桌面等无效目标，不播报 */
        const val WALK_MAX_BOX_AREA_RATIO = 0.35f

        /** 联网识别 API Key（从 local.properties 注入，不写死在源码里） */
        const val NETWORK_API_URL = "https://api.siliconflow.cn/v1/chat/completions"

        /** 联网识别 API Key：运行时从 BuildConfig 读取，避免泄露 */
        val NETWORK_API_KEY = BuildConfig.SILICONFLOW_API_KEY

        /** 联网识别模型 */
        const val NETWORK_MODEL = "Qwen/Qwen3-VL-8B-Instruct"

        /** 联网识别最大输出 token 数（30字以内的简短结果足够，降低生成延迟） */
        const val NETWORK_MAX_TOKENS = 60

        /** 联网识别周期间隔（毫秒）。大模型生成约 2-5 秒，间隔略长于生成时长可减少排队与延迟感 */
        const val NETWORK_INTERVAL_MS = 4000L

        /** 判定断网后的探测间隔（毫秒）：以更低频率继续尝试，成功后自动恢复联网 */
        const val NETWORK_PROBE_INTERVAL_MS = 10000L

        /** 联网识别请求超时（毫秒）。大模型生成通常 2-5 秒，8 秒超时足够且能更快识别真断网 */
        const val NETWORK_TIMEOUT_MS = 8000

        /** 联网请求连续失败多少次判定为断网（放宽到 4 次 ≈ 12 秒，避免网络一时波动误报断网） */
        const val NETWORK_FAIL_THRESHOLD = 4

        /** 上传图片长边上限（像素）：缩小图片可显著降低上传与识别延迟 */
        const val NETWORK_IMAGE_MAX_EDGE = 640

        /** 网络模式切换提示播报后的让路时长（毫秒）：期间其他播报不打断它 */
        const val NETWORK_ANNOUNCE_GRACE_MS = 1500L

        /** 离线刚检测到障碍物后的窗口（毫秒）：期间联网“无障碍”结论不播报，避免矛盾 */
        const val LOCAL_OBSTACLE_RECENT_MS = 2000L

        /** 联网识别提示词 */
        const val NETWORK_PROMPT =
            "你是视障人士的出行助手。请仔细看这张前方人行道画面，找出路面上所有可能挡路或绊倒人的立体障碍物，" +
                "包括但不限于：路灯杆、电线杆、护栏、交通锥、防撞柱、垃圾桶、共享单车、电动车、台阶、石墩、树、箱子等。" +
                "只回答障碍物本身（名称+大概方位），按距离由近到远。若画面里确实没有任何立体障碍物，只回答“无障碍物”四个字。" +
                "不要描述路面是否平整、光线、天气等与障碍物无关的内容，不超过30字。"

        /** 控制类语音命令 */
        const val CMD_STOP = "停止识别"
        const val CMD_START = "开始识别"
        const val CMD_SAVE = "保存画面"
        const val CMD_EXIT = "退出"

        /** 行走模式障碍物白名单·高优先级：行人/车辆/动物等直接威胁安全 */
        private val WALK_HIGH_PRIORITY = setOf(
            "person", "car", "bicycle", "motorcycle", "bus", "truck", "dog", "cat"
        )

        /** 行走模式障碍物白名单·中优先级：易绊倒或挡路的固定障碍物。
         *  注意：laptop/keyboard/mouse/remote/tv 等小电子物易被 COCO 误检（如树干→键盘），
         *  且人行道上不会真有笔记本挡路，已从白名单移除，避免误报。 */
        private val WALK_MID_PRIORITY = setOf(
            "bench", "fire hydrant", "stop sign", "traffic light", "potted plant",
            "chair", "dining table", "suitcase", "backpack", "refrigerator"
        )

        /** 行走模式实际播报的类别 = 高优先级 + 中优先级（其余类别在行走模式一律不播报） */
        private val WALK_PRIORITY_LABELS = WALK_HIGH_PRIORITY + WALK_MID_PRIORITY

        /** 语音命令词 → 检测模型英文类别（注意“书包/包”等需排在包含关系更长的词之前） */
        private val VOICE_KEYWORD_MAP = mapOf(
            "人" to "person",
            "椅子" to "chair",
            "凳子" to "chair",
            "长椅" to "bench",
            "公园椅" to "bench",
            "桌子" to "dining table",
            "餐桌" to "dining table",
            "手机" to "cell phone",
            "床" to "bed",
            "杯子" to "cup",
            "水杯" to "cup",
            "茶杯" to "cup",
            "保温杯" to "cup",
            "瓶子" to "bottle",
            "水瓶" to "bottle",
            "饮料瓶" to "bottle",
            "书包" to "backpack",
            "背包" to "backpack",
            "沙发" to "couch",
            "冰箱" to "refrigerator",
            "洗手间" to "toilet",
            "厕所" to "toilet",
            "马桶" to "toilet",
            "电视" to "tv",
            "电脑" to "laptop",
            "笔记本电脑" to "laptop",
            "书" to "book",
            "键盘" to "keyboard",
            "盆栽" to "potted plant",
            "花盆" to "potted plant",
            "雨伞" to "umbrella",
            "行李箱" to "suitcase",
            "狗" to "dog",
            "猫" to "cat",
            "自行车" to "bicycle",
            "汽车" to "car",
            "摩托车" to "motorcycle",
            "碗" to "bowl"
        )
    }
}

/**
 * 帧间稳定化器：解决逐帧检测结果的类别闪烁问题。
 *
 * 逻辑：按框中心把相邻帧中的同一物体关联成一条“轨迹”，对每条轨迹保存最近
 * [WINDOW_SIZE] 帧的类别做投票；只有得票类别连续 [STABLE_FRAMES] 帧不变，
 * 才输出该检测（类别用投票结果，框与得分用当前帧），从而避免显示与播报抖动。
 * 非线程安全，仅在单线程分析线程上调用。
 */
private class FrameStabilizer {

    private class Track {
        var centerX = 0f
        var centerY = 0f

        /** 最近若干帧的类别名（滑窗） */
        val labels = ArrayDeque<String>()

        /** 当前连续稳定的类别 */
        var stableLabel: String? = null

        /** 该稳定类别已连续出现的帧数 */
        var stableFrames = 0

        /** 最近一次命中的帧序号，用于淘汰过期轨迹 */
        var lastFrame = 0
    }

    private val tracks = ArrayList<Track>()
    private var frameIndex = 0

    /**
     * 输入一帧原始检测结果，返回“已稳定”的检测结果列表。
     *
     * @param detections 当前帧的检测结果（坐标为像素）
     * @param frameWidth 当前帧宽度，用于按比例计算中心匹配距离阈值
     */
    fun stabilize(detections: List<Detector.Detection>, frameWidth: Int): List<Detector.Detection> {
        frameIndex++

        // 淘汰长时间没有命中的轨迹
        tracks.removeAll { frameIndex - it.lastFrame > MAX_MISSED_FRAMES }

        val matchDistance = frameWidth * MATCH_DISTANCE_RATIO
        val matchDistanceSq = matchDistance * matchDistance

        val result = ArrayList<Detector.Detection>(detections.size)
        for (detection in detections) {
            val centerX = detection.box.centerX()
            val centerY = detection.box.centerY()

            // 找到中心最接近、且在阈值内的已有轨迹
            var nearest: Track? = null
            var nearestDistSq = Float.MAX_VALUE
            for (track in tracks) {
                val dx = track.centerX - centerX
                val dy = track.centerY - centerY
                val distSq = dx * dx + dy * dy
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq
                    nearest = track
                }
            }
            val matched = nearest?.takeIf { nearestDistSq <= matchDistanceSq }
            val track = matched ?: Track().also { tracks.add(it) }

            track.centerX = centerX
            track.centerY = centerY
            track.lastFrame = frameIndex

            // 滑窗投票：取窗口内出现次数最多的类别
            track.labels.addLast(detection.label)
            while (track.labels.size > WINDOW_SIZE) track.labels.removeFirst()
            val majority = track.labels
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: detection.label

            if (majority == track.stableLabel) {
                track.stableFrames++
            } else {
                track.stableLabel = majority
                track.stableFrames = 1
            }

            // 连续多帧一致才认为稳定，输出给界面与播报
            if (track.stableFrames >= STABLE_FRAMES) {
                result.add(Detector.Detection(majority, detection.box, detection.score))
            }
        }
        return result
    }

    /** 清空全部轨迹（停止识别时调用） */
    fun reset() {
        tracks.clear()
        frameIndex = 0
    }

    private companion object {
        /** 投票滑窗长度（帧） */
        const val WINDOW_SIZE = 5

        /** 连续一致帧数达到该值才认为稳定 */
        const val STABLE_FRAMES = 3

        /** 轨迹多久没命中就淘汰（帧） */
        const val MAX_MISSED_FRAMES = 15

        /** 判定为同一物体的中心距离阈值（占帧宽比例） */
        const val MATCH_DISTANCE_RATIO = 0.12f
    }
}
