package com.autoclicker.farsi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * سرویس اصلی کلیک خودکار با استفاده از Accessibility API اندروید ۱۰
 * دارای حالت تست آزمایشی ۵ کلیک، زمان‌بندی میلی‌ثانیه‌ای و تنظیم تعداد کلیک
 */
class AutoClickService : AccessibilityService() {

    companion object {
        var instance: AutoClickService? = null
        const val ACTION_START_CLICKING = "com.autoclicker.START"
        const val ACTION_STOP_CLICKING = "com.autoclicker.STOP"
        const val ACTION_TEST_RUN = "com.autoclicker.TEST_RUN"
        
        const val EXTRA_X = "extra_x"
        const val EXTRA_Y = "extra_y"
        const val EXTRA_INTERVAL = "extra_interval"
        const val EXTRA_COUNT = "extra_count"
        const val EXTRA_SCHEDULE_MS = "extra_schedule_ms"

        var isClicking = false
            private set
    }

    private var targetX: Float = 317f
    private var targetY: Float = 540f
    private var clickIntervalMs: Long = 25L
    private var maxClicks: Int = 40
    private var clicksDone: Int = 0

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        workerThread = HandlerThread("AutoClickWorker").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
        Log.d("AutoClickService", "Accessibility Service متصل و آماده است")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_RUN -> {
                // حالت تست عملکرد کلیک‌ها: ۵ کلیک سریع جهت بررسی صحت نقطه و سرعت
                targetX = intent.getIntExtra(EXTRA_X, targetX.toInt()).toFloat()
                targetY = intent.getIntExtra(EXTRA_Y, targetY.toInt()).toFloat()
                runTestMode()
            }
            ACTION_START_CLICKING -> {
                targetX = intent.getIntExtra(EXTRA_X, targetX.toInt()).toFloat()
                targetY = intent.getIntExtra(EXTRA_Y, targetY.toInt()).toFloat()
                clickIntervalMs = intent.getLongExtra(EXTRA_INTERVAL, clickIntervalMs)
                maxClicks = intent.getIntExtra(EXTRA_COUNT, maxClicks)
                val scheduledTimestamp = intent.getLongExtra(EXTRA_SCHEDULE_MS, 0L)

                if (scheduledTimestamp > System.currentTimeMillis()) {
                    scheduleStart(scheduledTimestamp)
                } else {
                    startClickingNow()
                }
            }
            ACTION_STOP_CLICKING -> {
                stopClicking()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * اجرای حالت تست آزمایشی (۵ کلیک برای اطمینان از عملکرد درست)
     */
    private fun runTestMode() {
        if (isClicking) return
        isClicking = true
        var testCount = 0
        vibrateBriefly()

        broadcastStatus("در حال اجرای حالت تست آزمایشی (۵ کلیک)...", 0, true)

        val testRunnable = object : Runnable {
            override fun run() {
                if (!isClicking || testCount >= 5) {
                    isClicking = false
                    vibrateBriefly()
                    broadcastStatus("حالت تست با موفقیت انجام شد ✓", 5, false)
                    return
                }

                dispatchClick(targetX, targetY) { success ->
                    testCount++
                    broadcastStatus("تست: کلیک $testCount از ۵", testCount, true)
                    if (isClicking) {
                        workerHandler?.postDelayed(this, 120L)
                    }
                }
            }
        }
        workerHandler?.post(testRunnable)
    }

    /**
     * زمان‌بندی شروع با ساعت ۲۴ ساعته و دقت میلی‌ثانیه‌ای
     */
    private fun scheduleStart(targetEpochMs: Long) {
        val delay = targetEpochMs - System.currentTimeMillis()
        if (delay > 0) {
            broadcastStatus("منتظر ساعت شروع تعیین‌شده...", 0, false)
            workerHandler?.postDelayed({
                while (System.currentTimeMillis() < targetEpochMs) {
                    Thread.yield()
                }
                startClickingNow()
            }, (delay - 40).coerceAtLeast(0))
        } else {
            startClickingNow()
        }
    }

    private fun startClickingNow() {
        if (isClicking) return
        isClicking = true
        clicksDone = 0
        vibrateBriefly()

        broadcastStatus("شروع کلیک‌های اصلی...", clicksDone, true)
        clickLoop()
    }

    private val clickRunnable = object : Runnable {
        override fun run() {
            if (!isClicking) return

            // توقف اتوماتیک بر اساس تعداد کلیک‌های تعیین‌شده (اگر ۰ نباشد)
            if (maxClicks > 0 && clicksDone >= maxClicks) {
                stopClicking()
                vibrateBriefly()
                broadcastStatus("اتمام تعداد کلیک‌ها ($clicksDone کلیک انجام شد)", clicksDone, false)
                return
            }

            dispatchClick(targetX, targetY) { success ->
                clicksDone++
                broadcastStatus("کلیک $clicksDone از " + if (maxClicks > 0) "$maxClicks" else "نامحدود", clicksDone, true)
                
                if (isClicking) {
                    workerHandler?.postDelayed(this, clickIntervalMs)
                }
            }
        }
    }

    private fun clickLoop() {
        workerHandler?.post(clickRunnable)
    }

    /**
     * ارسال ژست کلیک سیستمی با GestureDescription
     */
    private fun dispatchClick(x: Float, y: Float, onComplete: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 5L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete(false)
            }
        }, null)
    }

    fun stopClicking() {
        isClicking = false
        workerHandler?.removeCallbacksAndMessages(null)
        broadcastStatus("عملیات متوقف شد", clicksDone, false)
    }

    private fun vibrateBriefly() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun broadcastStatus(message: String, count: Int, running: Boolean) {
        val intent = Intent("com.autoclicker.STATUS_UPDATE").apply {
            putExtra("message", message)
            putExtra("count", count)
            putExtra("running", running)
        }
        sendBroadcast(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopClicking()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopClicking()
        workerThread?.quitSafely()
        instance = null
    }
}