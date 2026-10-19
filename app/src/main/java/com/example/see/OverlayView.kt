package com.example.see

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max

/**
 * 叠加在 PreviewView 之上的自定义 View，用于在摄像头画面上绘制物体检测框。
 *
 * 检测框坐标来自本地检测模型，基于「旋转摆正后」的图像像素坐标系；
 * onDraw 时再按 PreviewView 的 FILL_CENTER 规则映射到本 View 的坐标系。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一条检测结果：框（图像像素坐标）+ 显示名称（中文）+ 估算距离(米，<=0 表示未知) + 方位 */
    data class Detection(
        val box: RectF,
        val label: String,
        val distanceMeters: Float,
        val direction: String
    )

    private var detections: List<Detection> = emptyList()

    /** 预警物体在 detections 中的下标；-1 表示没有预警物体 */
    private var dangerIndex = -1

    // 源图像（旋转摆正后）的尺寸，用于坐标换算
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val density = resources.displayMetrics.density

    /** 黄色粗边框 */
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        strokeWidth = 4f * density
    }

    /** 标签黑色半透明背景 */
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x99000000.toInt()
    }

    /** 标签文字（黄色加粗） */
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
        textSize = 16f * density
        isFakeBoldText = true
    }

    /** 预警（近距离障碍物）红色粗边框 */
    private val dangerBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 6f * density
    }

    /** 预警标签文字（红色加粗） */
    private val dangerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
        textSize = 16f * density
        isFakeBoldText = true
    }

    /** 检测框文案模板 */
    private val distanceTemplate = context.getString(R.string.detection_distance)
    private val dangerMark = context.getString(R.string.detection_danger)

    /**
     * 更新检测结果并刷新。
     *
     * @param detections   检测框列表（坐标为旋转摆正后的图像像素）
     * @param sourceWidth  旋转摆正后图像的宽度
     * @param sourceHeight 旋转摆正后图像的高度
     */
    fun setResults(detections: List<Detection>, sourceWidth: Int, sourceHeight: Int) {
        this.detections = detections
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        this.dangerIndex = -1
        invalidate()
    }

    /**
     * 设置预警物体的下标（对应 detections 列表的位置），绘制时该框变红并加“！危险”标记。
     *
     * @param index 预警物体下标；传 -1 表示取消预警
     */
    fun setDangerIndex(index: Int) {
        dangerIndex = index
        invalidate()
    }

    /** 清空所有检测框 */
    fun clear() {
        detections = emptyList()
        dangerIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // PreviewView 默认 FILL_CENTER：等比放大填满控件，居中裁剪
        val scale = max(viewWidth / sourceWidth, viewHeight / sourceHeight)
        val offsetX = (viewWidth - sourceWidth * scale) / 2f
        val offsetY = (viewHeight - sourceHeight * scale) / 2f

        val fontMetrics = labelTextPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val padding = 4f * density

        detections.forEachIndexed { index, detection ->
            val isDanger = index == dangerIndex
            val rectPaint = if (isDanger) dangerBoxPaint else boxPaint
            val textPaint = if (isDanger) dangerTextPaint else labelTextPaint

            // 映射到 View 坐标
            val rect = RectF(
                detection.box.left * scale + offsetX,
                detection.box.top * scale + offsetY,
                detection.box.right * scale + offsetX,
                detection.box.bottom * scale + offsetY
            )
            canvas.drawRect(rect, rectPaint)

            // 标签文本，如 "人 1.2米 正前方" / "Person 1.2m ahead"；预警时追加 "！危险" / "! DANGER"
            val text = buildString {
                append(detection.label)
                if (detection.distanceMeters > 0f) {
                    append(' ')
                    append(
                        distanceTemplate.format(
                            String.format(Locale.CHINA, "%.1f", detection.distanceMeters)
                        )
                    )
                }
                if (detection.direction.isNotEmpty()) {
                    append(' ')
                    append(detection.direction)
                }
                if (isDanger) {
                    append(' ')
                    append(dangerMark)
                }
            }
            val textWidth = textPaint.measureText(text)

            // 标签默认贴在框上方；若超出画面顶部则画在框内
            var bgTop = rect.top - textHeight - padding * 2f
            if (bgTop < 0f) bgTop = rect.top
            val bgRect = RectF(
                rect.left,
                bgTop,
                rect.left + textWidth + padding * 2f,
                bgTop + textHeight + padding * 2f
            )

            canvas.drawRect(bgRect, labelBgPaint)
            canvas.drawText(
                text,
                bgRect.left + padding,
                bgRect.top + padding - fontMetrics.ascent,
                textPaint
            )
        }
    }

    companion object {

        /** 检测模型英文标签 → 中文名称映射表（键统一小写） */
        private val LABEL_ZH: Map<String, String> = mapOf(
            "person" to "人",
            "bicycle" to "自行车",
            "car" to "汽车",
            "motorcycle" to "摩托车",
            "airplane" to "飞机",
            "bus" to "公交车",
            "train" to "火车",
            "truck" to "卡车",
            "boat" to "船",
            "traffic light" to "红绿灯",
            "fire hydrant" to "消防栓",
            "stop sign" to "停车标志",
            "parking meter" to "停车计时器",
            "bench" to "长椅",
            "bird" to "鸟",
            "cat" to "猫",
            "dog" to "狗",
            "horse" to "马",
            "sheep" to "羊",
            "cow" to "牛",
            "elephant" to "大象",
            "bear" to "熊",
            "zebra" to "斑马",
            "giraffe" to "长颈鹿",
            "backpack" to "书包",
            "umbrella" to "雨伞",
            "handbag" to "手提包",
            "tie" to "领带",
            "suitcase" to "行李箱",
            "frisbee" to "飞盘",
            "skis" to "滑雪板",
            "snowboard" to "单板滑雪板",
            "sports ball" to "球",
            "kite" to "风筝",
            "baseball bat" to "棒球棒",
            "baseball glove" to "棒球手套",
            "skateboard" to "滑板",
            "surfboard" to "冲浪板",
            "tennis racket" to "网球拍",
            "bottle" to "瓶子",
            "wine glass" to "酒杯",
            "cup" to "杯子",
            "fork" to "叉子",
            "knife" to "刀",
            "spoon" to "勺子",
            "bowl" to "碗",
            "banana" to "香蕉",
            "apple" to "苹果",
            "sandwich" to "三明治",
            "orange" to "橙子",
            "broccoli" to "西兰花",
            "carrot" to "胡萝卜",
            "hot dog" to "热狗",
            "pizza" to "披萨",
            "donut" to "甜甜圈",
            "cake" to "蛋糕",
            "chair" to "椅子",
            "couch" to "沙发",
            "potted plant" to "盆栽",
            "bed" to "床",
            "dining table" to "餐桌",
            "table" to "餐桌",
            "toilet" to "马桶",
            "tv" to "电视",
            "laptop" to "笔记本电脑",
            "mouse" to "鼠标",
            "remote" to "遥控器",
            "keyboard" to "键盘",
            "cell phone" to "手机",
            "mobile phone" to "手机",
            "phone" to "手机",
            "microwave" to "微波炉",
            "oven" to "烤箱",
            "toaster" to "烤面包机",
            "sink" to "水槽",
            "refrigerator" to "冰箱",
            "book" to "书",
            "clock" to "钟表",
            "vase" to "花瓶",
            "scissors" to "剪刀",
            "teddy bear" to "泰迪熊",
            "hair drier" to "吹风机",
            "toothbrush" to "牙刷"
        )

        /** 把检测模型返回的英文标签转成中文名称，没有映射则原样返回 */
        fun toChinese(label: String): String = LABEL_ZH[label.lowercase()] ?: label
    }
}
