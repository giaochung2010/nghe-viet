package com.ngheviet.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.graphics.Color
import android.view.View
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var en: TextView
    private lateinit var vi: TextView
    private lateinit var metrics: TextView
    private var preparing = false
    private val repaint = object : Runnable {
        override fun run() {
            if (!preparing) state.text = SessionState.status
            en.text = SessionState.english.ifBlank { "Lời tiếng Anh sẽ xuất hiện ở đây" }
            vi.text = SessionState.vietnamese.ifBlank { "Nghĩa tiếng Việt sẽ được đọc tự động" }
            metrics.text = SessionState.metrics
            main.postDelayed(this, 300)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(243,248,247)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(Color.rgb(243,248,247)) }
        root.setOnApplyWindowInsetsListener { v, inset -> val bars = inset.getInsets(android.view.WindowInsets.Type.systemBars()); v.setPadding(24, bars.top+24,24,bars.bottom+24); inset }
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
        fun text(value: String, size: Float = 16f): TextView = TextView(this).apply { text=value; textSize=size; setTextColor(Color.rgb(24,55,54)); setPadding(0,10,0,10); root.addView(this) }
        text("NGHE VIỆT", 30f)
        text("Tiếng Anh → nghĩa tiếng Việt vào tai nghe",18f)
        text("Bản thử 0.1 • Offline sau khi chuẩn bị\nMic điện thoại thu • P30i phát • Tiếp tục khi khóa màn hình")
        state = text("Chưa bắt đầu",18f)
        fun button(label:String, action:()->Unit) { root.addView(Button(this).apply { text=label; setOnClickListener { action() } }) }
        button("1. Tải model dịch (Wi-Fi)") {
            if (SessionState.running || preparing) return@button
            preparing = true; state.text = "Đang tải model dịch qua Wi-Fi…"
            val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.VIETNAMESE).build())
            client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener { SessionState.status="Model dịch sẵn sàng. Kiểm tra giọng Việt offline rồi bắt đầu." }
                .addOnFailureListener { SessionState.status="Tải chưa xong: ${it.message}" }
                .addOnCompleteListener { preparing=false; client.close() }
        }
        button("2. Cài giọng Google TTS tiếng Việt") { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        text("Chọn Google Speech Services → dữ liệu giọng nói → Tiếng Việt. App chỉ chấp nhận giọng không cần mạng. Kết nối tai nghe trước khi bắt đầu.")
        val rateLabel = text("Tốc độ cơ bản: 1,7×; tự tăng tối đa 2,0×")
        root.addView(SeekBar(this).apply { max=10; progress=7; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s:SeekBar?, p:Int, user:Boolean) { SessionState.rate=1f+p/10f; rateLabel.text="Tốc độ cơ bản: %.1f×; tự tăng tối đa 2,0×".format(SessionState.rate) }
            override fun onStartTrackingTouch(s:SeekBar?) {}
            override fun onStopTrackingTouch(s:SeekBar?) {}
        }) })
        button("Bắt đầu nghe") {
            if (preparing) return@button
            val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT>=33) needed += Manifest.permission.POST_NOTIFICATIONS
            val missing=needed.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),10) else startListening()
        }
        button("Tạm dừng / Dừng") { startService(Intent(this,ListenService::class.java).setAction(ListenService.STOP)) }
        button("Cài đặt pin của ứng dụng") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
        text("Trên Samsung, nếu phiên bị ngắt khi khóa màn hình: đặt Pin của app thành Không hạn chế. Bản dịch theo từ có thể sai ngữ cảnh; app không phân biệt giọng của cậu với người đối diện.")
        en=text("",21f); vi=text("",23f); metrics=text("",13f)
        text("Không lưu bản thu hay lịch sử ra đĩa. ASR được đóng gói sẵn; Internet dùng cho tải model ML Kit. Mục tiêu phản hồi sớm, chưa bảo đảm trễ 1 giây.",13f)
    }
    private fun startListening() { startForegroundService(Intent(this,ListenService::class.java).setAction(ListenService.START)) }
    override fun onRequestPermissionsResult(code:Int, permissions:Array<out String>, results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==10 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED) startListening()
    }
    override fun onResume() { super.onResume(); main.post(repaint) }
    override fun onPause() { main.removeCallbacks(repaint); super.onPause() }
}
