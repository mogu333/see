package com.example.see

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.iflytek.aikit.core.AiAudio
import com.iflytek.aikit.core.AiHandle
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.AiListener
import com.iflytek.aikit.core.AiRequest
import com.iflytek.aikit.core.AiResponse
import com.iflytek.aikit.core.AiStatus
import com.iflytek.aikit.core.BaseLibrary
import com.iflytek.aikit.core.CoreListener
import com.iflytek.aikit.core.ErrType
import com.iflytek.aikit.core.LogLvl
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

/**
 * 讯飞 AIKit 离线命令词识别封装（连续聆听版）。
 *
 * 使用流程：init(context) 完成鉴权与模型准备；startContinuousListening() 进入连续聆听，
 * 每识别完一轮会自动开启下一轮；识别结果以文字形式回调（已切回主线程）。
 *
 * 说明：除 [onResult] 回调内部外，其余公开方法均需在主线程调用。
 */
class VoiceRecognizer private constructor() {

    /** 模型工作目录，init 后有效 */
    private var workDir = ""

    /** Application Context，用于录音前校验权限 */
    private lateinit var appContext: Context

    /** 是否已发起 init */
    @Volatile
    private var initialized = false

    /** 引擎与模型是否已就绪（initEntry 完成后为 true） */
    @Volatile
    private var engineReady = false

    /** 是否处于连续聆听模式 */
    @Volatile
    private var continuous = false

    /** 是否暂停（如 TTS 播报期间，避免听到自己的声音） */
    @Volatile
    private var paused = false

    /** 当前是否有一轮识别在跑 */
    @Volatile
    private var recording = false

    /** 是否已排程下一轮，防止重复排程 */
    @Volatile
    private var restartPending = false

    /** 命令词表是否已加载（只加载一次） */
    @Volatile
    private var dataLoaded = false

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var aiHandle: AiHandle? = null

    /** 识别结果回调 */
    private var resultCallback: ((String) -> Unit)? = null

    /** 主线程 Handler，用于循环调度与结果回调 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 初始化：拷贝模型资源 → 设置日志 → 子线程 initEntry → 就绪后按需开启聆听。
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        appContext = context.applicationContext
        workDir = context.getExternalFilesDir(null)!!.absolutePath + "/iflytek"
        copyAssets(context)

        File("$workDir/aikit").mkdirs()
        AiHelper.getInst().setLogInfo(LogLvl.VERBOSE, 1, "$workDir/aikit/aeeLog.txt")

        val params = BaseLibrary.Params.builder()
            .appId(APPID)
            .apiKey(APIKEY)
            .apiSecret(APISECRET)
            .workDir(workDir)
            .build()

        // 授权回调 + 识别结果回调
        AiHelper.getInst().registerListener(coreListener)
        AiHelper.getInst().registerListener(ABILITYID, edListener)

        // initEntry 必须在子线程执行
        Thread {
            try {
                AiHelper.getInst().initEntry(context.applicationContext, params)
                Log.i(TAG, "initEntry 完成")
            } catch (e: Exception) {
                Log.e(TAG, "initEntry 异常", e)
            }
            mainHandler.post {
                engineReady = true
                // 若在引擎就绪前已请求聆听，这里补上第一轮
                if (continuous && !paused && !recording) startSession()
            }
        }.start()
    }

    /**
     * 开始连续聆听：每识别完一轮（onResult 回调）自动开启下一轮。
     *
     * @param listener 识别到文字后的回调（主线程）
     */
    fun startContinuousListening(listener: (String) -> Unit) {
        resultCallback = listener
        continuous = true
        paused = false
        startSession()
    }

    /** 手动立即重新开始一轮聆听（“按住说话”按钮备用） */
    fun restartListening() {
        if (!continuous) return
        restartLoop(0L)
    }

    /** 暂停聆听：TTS 播报期间调用，避免把播报内容当成命令 */
    fun pauseListening() {
        mainHandler.post {
            paused = true
            mainHandler.removeCallbacks(restartRunnable)
            restartPending = false
            endSession()
        }
    }

    /** 恢复聆听：播报结束后调用 */
    fun resumeListening() {
        mainHandler.post {
            paused = false
            startSession()
        }
    }

    /** 停止聆听（Activity 销毁或关闭识别时调用），不会自动重启 */
    fun stopListening() {
        continuous = false
        paused = false
        mainHandler.removeCallbacks(restartRunnable)
        restartPending = false
        endSession()
        resultCallback = null
    }

    /** 释放 AIKit 资源，允许之后重新 init */
    fun release() {
        stopListening()
        if (!initialized) return
        initialized = false
        engineReady = false
        dataLoaded = false
        try {
            AiHelper.getInst().unLoadData(ABILITYID, "FSA", 0)
            AiHelper.getInst().engineUnInit(ABILITYID)
            AiHelper.getInst().unInit()
        } catch (e: Exception) {
            Log.e(TAG, "释放 AIKit 异常", e)
        }
    }

    /** 开启一轮识别：首次会初始化引擎并加载命令词表 */
    private fun startSession() {
        if (!continuous || paused || recording || !engineReady) return
        if (!hasRecordPermission()) return

        // 引擎初始化 + 加载 FSA 命令词表，只做一次
        if (!dataLoaded) {
            val initRequest = AiRequest.builder()
                .param("decNetType", "fsa")
                .param("punishCoefficient", "0.0")
                .param("wfst_addType", "0")
                .build()
            AiHelper.getInst().engineInit(ABILITYID, initRequest)

            val fsaPath = "$workDir/CNENESR/fsa/cn_fsa.txt"
            val loadRequest = AiRequest.builder()
                .customText("FSA", fsaPath, 0)
                .build()
            AiHelper.getInst().loadData(ABILITYID, loadRequest)
            AiHelper.getInst().specifyDataSet(ABILITYID, "FSA", intArrayOf(0))
            dataLoaded = true
        }

        val startRequest = AiRequest.builder()
            .param("languageType", 0)
            .param("vadEndGap", 100)
            .param("vadOn", true)
            .param("beamThreshold", 20)
            .param("hisGramThreshold", 3000)
            .param("vadLinkOn", false)
            .param("vadSpeechEnd", 80)
            .param("vadResponsetime", 1000)
            .param("postprocOn", false)
            .build()
        aiHandle = AiHelper.getInst().start(ABILITYID, startRequest, null)

        startRecordLoop()

        // 看门狗：一轮迟迟没有结果时强制重启，避免聆听卡死
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, MAX_SESSION_MS)
    }

    /** 结束当前这轮识别并释放本轮录音资源（同步完成，保证下一轮不会与本轮重叠） */
    private fun endSession() {
        mainHandler.removeCallbacks(watchdogRunnable)
        if (!recording && aiHandle == null) return

        // 通知录音线程退出
        recording = false
        try {
            recordThread?.join(500)
        } catch (_: InterruptedException) {
        }
        recordThread = null

        // 发送 END 尾帧并结束本轮引擎会话
        writeAudio(ByteArray(0), AiStatus.END)
        aiHandle?.let { AiHelper.getInst().end(it) }
        aiHandle = null
        audioRecord = null
    }

    /**
     * 结束本轮并在延时后开启下一轮；若已暂停或不处于连续聆听则不再排程。
     * restartPending 保证同一时刻只会排一次，避免重复。
     */
    private fun restartLoop(delayMs: Long) {
        endSession()
        if (!continuous || paused) return
        if (restartPending) return
        restartPending = true
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private val restartRunnable = Runnable {
        restartPending = false
        startSession()
    }

    private val watchdogRunnable = Runnable {
        if (continuous && !paused && recording) restartLoop(RESTART_DELAY_MS)
    }

    private fun hasRecordPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少录音权限，无法开始识别")
            return false
        }
        return true
    }

    /** 创建 AudioRecord，并在子线程持续读取音频写入引擎 */
    private fun startRecordLoop() {
        // 就地做权限检查，供 Lint 识别（缺少权限时直接返回，不创建 AudioRecord）
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "缺少录音权限，无法开始识别")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuffer > 0) minBuffer * 2 else SAMPLE_RATE * 2
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            record.release()
            return
        }

        audioRecord = record
        recording = true
        record.startRecording()

        recordThread = Thread {
            val buffer = ByteArray(1280)
            var firstFrame = true
            while (recording) {
                val len = record.read(buffer, 0, buffer.size)
                if (len <= 0) continue
                writeAudio(
                    buffer.copyOf(len),
                    if (firstFrame) AiStatus.BEGIN else AiStatus.CONTINUE
                )
                firstFrame = false
            }
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            audioRecord = null
        }.also { it.start() }
    }

    /** 把一帧音频写入引擎并读取结果（结果通过 edListener 回调） */
    private fun writeAudio(data: ByteArray, status: AiStatus) {
        val handle = aiHandle ?: return
        try {
            val audio = AiAudio.get("audio").data(data).status(status).valid()
            val request = AiRequest.builder().payload(audio).build()
            AiHelper.getInst().write(request, handle)
            AiHelper.getInst().read(ABILITYID, handle)
        } catch (e: Exception) {
            Log.e(TAG, "写入音频异常", e)
        }
    }

    /** 把 assets/iflytek 下的四个模型目录递归拷贝到 workDir（已存在则跳过） */
    private fun copyAssets(context: Context) {
        val assets = context.assets
        for (folder in ASSET_FOLDERS) {
            copyAssetDir(assets, "iflytek/$folder", "$workDir/$folder")
        }
    }

    private fun copyAssetDir(assets: AssetManager, assetPath: String, destPath: String) {
        val dest = File(destPath)
        if (dest.exists()) return

        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // 叶子节点：普通文件
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            return
        }

        dest.mkdirs()
        for (child in children) {
            copyAssetDir(assets, "$assetPath/$child", "$destPath/$child")
        }
    }

    /** 鉴权回调：code == 0 表示鉴权成功 */
    private val coreListener = object : CoreListener {
        override fun onAuthStateChange(type: ErrType?, code: Int) {
            if (code == 0) {
                Log.i(TAG, "鉴权成功")
            } else {
                Log.e(TAG, "鉴权失败 type=$type code=$code")
            }
        }
    }

    /** 识别结果回调：一轮结束，先结束本轮再回调上层，随后自动开启下一轮 */
    private val edListener = object : AiListener {
        override fun onResult(code: Int, data: List<AiResponse>?, userData: Any?) {
            val text = extractText(data)
            mainHandler.post {
                // 先结束本轮（关麦克风），再回调上层播报，避免听到自己的播报
                restartLoop(RESTART_DELAY_MS)
                if (text.isNotEmpty()) resultCallback?.invoke(text)
            }
        }

        override fun onEvent(code: Int, event: Int, data: List<AiResponse>?, userData: Any?) {
            // 事件回调暂不处理
        }

        override fun onError(code: Int, err: Int, msg: String?, userData: Any?) {
            Log.e(TAG, "识别出错 code=$code err=$err msg=$msg")
            mainHandler.post { restartLoop(RESTART_DELAY_MS) }
        }
    }

    /** 从讯飞返回结果里取出识别到的文字 */
    private fun extractText(data: List<AiResponse>?): String {
        val responses = data ?: return ""
        val builder = StringBuilder()
        for (response in responses) {
            val key = response.key ?: continue
            if (key.contains("plain") || key.contains("pgs")) {
                builder.append(String(response.value ?: ByteArray(0), Charset.forName("GBK")))
            }
        }
        return builder.toString()
    }

    companion object {
        private const val TAG = "VoiceRecognizer"

        // 讯飞 AIKit 凭证：从 local.properties 注入 BuildConfig，不写死在源码里
        private val APPID = BuildConfig.IFLYTEK_APP_ID
        private val APIKEY = BuildConfig.IFLYTEK_API_KEY
        private val APISECRET = BuildConfig.IFLYTEK_API_SECRET
        private val ABILITYID = BuildConfig.IFLYTEK_ABILITY_ID

        /** 需要拷贝的模型目录 */
        private val ASSET_FOLDERS = arrayOf("CNENESR", "ivw", "xtts", "aisound")

        /** 录音采样率 */
        private const val SAMPLE_RATE = 16000

        /** 一轮结束到下一轮开始之间的间隔，避免过于频繁地反复重启 */
        private const val RESTART_DELAY_MS = 300L

        /** 一轮识别的最长时间：超过则强制重启，避免聆听卡死 */
        private const val MAX_SESSION_MS = 6000L

        @Volatile
        private var instance: VoiceRecognizer? = null

        fun getInstance(): VoiceRecognizer =
            instance ?: synchronized(this) {
                instance ?: VoiceRecognizer().also { instance = it }
            }
    }
}
