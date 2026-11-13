package com.ngheviet.app

import android.app.*
import android.Manifest
import android.content.pm.PackageManager
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.speech.tts.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import com.k2fsa.sherpa.onnx.*
import java.util.Locale
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class ListenService : Service() {
    companion object { const val START="start"; const val STOP="stop"; private const val CHANNEL="listening" }
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var active=false
    private var closing=false
    private var initializing=false
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var recorder:AudioRecord?=null
    private var capture:Thread?=null
    private var worker:Thread?=null
    private var wake:PowerManager.WakeLock?=null
    private var tts:TextToSpeech?=null
    private var translator:Translator?=null
    private var focus:AudioFocusRequest?=null
    private val translationQueue=ArrayBlockingQueue<String>(160)
    private val speechQueue=java.util.ArrayDeque<String>() // main thread only
    private var speaking=false
    private var utterance=0
    private var speakingId=""
    private var speakStarted=0L
    private val pending=AtomicInteger(0)
    private var registered=false
    private var audioCallbackRegistered=false
    private val audioAttributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val deviceCallback=object:AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(devices:Array<out AudioDeviceInfo>) {
            // Do not switch automatically to another device after a headset disappears.
            if(devices.any { isHeadphone(it) } && (active || initializing)) finish("Đã dừng: tai nghe bị ngắt. Kết nối lại rồi bấm Bắt đầu.")
        }
    }
    private val noisy=object:BroadcastReceiver() {
        override fun onReceive(context:Context?, intent:Intent?) { finish("Đã dừng: đường âm thanh thay đổi.") }
    }
    private val watchdog=object:Runnable {
        override fun run() {
            if (closing) return
            if (active && speaking && SystemClock.elapsedRealtime()-speakStarted>20_000) {
                finish("Đã dừng: giọng đọc không phản hồi. Kiểm tra Google TTS."); return
            }
            main.postDelayed(this,1000)
        }
    }
    override fun onBind(intent:Intent?) : IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Phiên dịch đang chạy",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?, flags:Int, id:Int):Int {
        if(intent?.action==STOP) { finish("Đã dừng. Bấm Bắt đầu để nghe tiếp."); return START_NOT_STICKY }
        if(active || initializing || closing) return START_NOT_STICKY
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            finish("Cần cấp quyền mic và thiết bị lân cận từ màn hình ứng dụng."); return START_NOT_STICKY
        }
        startForeground(1,notification("Đang chuẩn bị…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        initializing=true
        try {
            check(headphones()!=null) { "Kết nối và đeo P30i trước khi bắt đầu." }
            audioManager.registerAudioDeviceCallback(deviceCallback,main); audioCallbackRegistered=true
            if(Build.VERSION.SDK_INT>=33) registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            registered=true
            setStatus("Đang kiểm tra model và giọng offline…")
            val model=TranslateRemoteModel.Builder(TranslateLanguage.VIETNAMESE).build()
            RemoteModelManager.getInstance().isModelDownloaded(model).addOnSuccessListener { downloaded ->
                if (closing) return@addOnSuccessListener
                if(!downloaded) finish("Cần bấm Tải model dịch qua Wi-Fi trước.") else initializeVoice()
            }.addOnFailureListener { finish("Không kiểm tra được model: ${it.message}") }
        } catch(e:Exception) { finish(e.message ?: "Không khởi động được phiên.") }
        return START_NOT_STICKY
    }
    private fun initializeVoice() {
        // Initialization callback is delivered after construction on the main looper.
        tts=TextToSpeech(this,{ code -> main.post {
            if(closing) return@post
            if(code!=TextToSpeech.SUCCESS) { finish("Không khởi tạo được Google TTS. Cài Speech Services by Google."); return@post }
            try {
                val voice=tts?.voices?.filter { it.locale.language=="vi" && !it.isNetworkConnectionRequired && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                    ?.sortedWith(compareBy<Voice> { it.latency }.thenByDescending { it.quality })?.firstOrNull()
                check(voice!=null) { "Chưa có giọng Việt offline của Google. Vào Cài giọng → tải Tiếng Việt." }
                check(tts?.setVoice(voice)==TextToSpeech.SUCCESS) { "Không chọn được giọng Việt offline." }
                tts?.setAudioAttributes(audioAttributes)
                tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
                    override fun onStart(id:String?) {}
                    override fun onDone(id:String?) { main.post { completeSpeech(id) } }
                    @Deprecated("Deprecated in Java") override fun onError(id:String?) { main.post { finish("Đã dừng: Google TTS không đọc được.") } }
                    override fun onError(id:String?, code:Int) { main.post { finish("Đã dừng: lỗi Google TTS $code") } }
                })
                beginCapture()
            } catch(e:Exception) { finish(e.message ?: "Giọng đọc chưa sẵn sàng.") }
        } },"com.google.android.tts")
    }
    private fun beginCapture() {
        try {
            check(headphones()!=null) { "Tai nghe đã ngắt." }
            val f=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener({ change -> if(change<0) finish("Đã dừng: cuộc gọi hoặc ứng dụng khác đang dùng âm thanh.") },main).build()
            check(audioManager.requestAudioFocus(f)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Chưa được cấp quyền sử dụng âm thanh." }
            focus=f
            translator=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.VIETNAMESE).build())
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"NgheViet:Session").apply { acquire(2*60*60*1000L) }
            // Explicit two-hour session limit prevents a stale lock after an abandoned session.
            main.postDelayed({ if(!closing) finish("Đã đủ 2 giờ. Bấm Bắt đầu để tiếp tục.") },2*60*60*1000L)
            active=true; initializing=false; SessionState.running=true
            SessionState.english=""; SessionState.vietnamese=""
            worker=Thread({ translateLoop() },"NgheViet-translation").also { it.start() }
            capture=Thread({ captureLoop() },"NgheViet-asr").also { it.start() }
            main.post(watchdog)
        } catch(e:Exception) { finish(e.message ?: "Không mở được âm thanh.") }
    }
    private fun captureLoop() {
        var recognizer:OnlineRecognizer?=null
        var stream:OnlineStream?=null
        var localRecorder:AudioRecord?=null
        try {
            val config=OnlineRecognizerConfig(
                featConfig=FeatureConfig(sampleRate=16000, featureDim=80),
                modelConfig=OnlineModelConfig(
                    transducer=OnlineTransducerModelConfig(
                        encoder="asr/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                        decoder="asr/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                        joiner="asr/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
                    tokens="asr/tokens.txt", numThreads=2, provider="cpu"),
                endpointConfig=EndpointConfig(EndpointRule(false,2.4f,0f),EndpointRule(true,0.7f,0f),EndpointRule(false,0f,15f)))
            recognizer=OnlineRecognizer(assets,config)
            stream=recognizer.createStream()
            if(!active) return
            val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(minimum>0) { "Mic không hỗ trợ âm thanh 16 kHz." }
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) throw SecurityException("Quyền mic đã bị thu hồi.")
            localRecorder=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum*4,16000)).build()
            check(localRecorder.state==AudioRecord.STATE_INITIALIZED) { "Không khởi tạo được mic." }
            val builtIn=audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC }
            check(builtIn!=null && localRecorder.setPreferredDevice(builtIn)) { "Không chọn được mic điện thoại." }
            recorder=localRecorder
            localRecorder.startRecording()
            check(localRecorder.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "Mic chưa thu được tiếng." }
            val shorts=ShortArray(1600)
            val committer=WordCommitter()
            val began=SystemClock.elapsedRealtime()
            var firstCommit=true
            main.post { if(active) setStatus("Đang nghe Anh → Việt • Có thể khóa màn hình") }
            while(active) {
                val n=localRecorder.read(shorts,0,shorts.size,AudioRecord.READ_BLOCKING)
                if(!active) break
                check(n>0) { "Mic bị gián đoạn ($n)." }
                check(localRecorder.routedDevice?.type==AudioDeviceInfo.TYPE_BUILTIN_MIC) { "Đường thu đã đổi khỏi mic điện thoại." }
                stream.acceptWaveform(FloatArray(n) { shorts[it]/32768f },16000)
                while(active && recognizer.isReady(stream)) recognizer.decode(stream)
                val text=recognizer.getResult(stream).text
                SessionState.english=text
                val endpoint=recognizer.isEndpoint(stream)
                val now=SystemClock.elapsedRealtime()
                // No explicit wait for sentence endings. Warm-up allows the first second of audio.
                if(now-began>=1000 || endpoint) {
                    val pieces=committer.update(text,now,endpoint)
                    for(piece in pieces) {
                        if(!translationQueue.offer(piece)) throw IllegalStateException("Bản dịch tồn quá nhiều. Đã dừng, không tự bỏ từ.")
                        if(firstCommit) { firstCommit=false; SessionState.metrics="Đoạn đầu sau ${now-began} ms (chưa gồm dịch/đọc)" }
                    }
                }
                if(endpoint) { recognizer.reset(stream); committer.reset() }
            }
        } catch(e:Throwable) { if(active) main.post { finish("Đã dừng: ${e.message ?: e.javaClass.simpleName}") } }
        finally {
            try { localRecorder?.stop() } catch(_:Exception) {}
            localRecorder?.release(); recorder=null
            stream?.release(); recognizer?.release()
        }
    }
    private fun translateLoop() {
        try {
            while(active) {
                val source=translationQueue.poll(500,TimeUnit.MILLISECONDS) ?: continue
                val before=SystemClock.elapsedRealtime()
                val target=Tasks.await(translator!!.translate(source),10,TimeUnit.SECONDS)
                if(!active) break
                val ms=SystemClock.elapsedRealtime()-before
                main.post {
                    if(!active) return@post
                    SessionState.vietnamese="$source → $target"
                    SessionState.metrics="Dịch: ${ms} ms • Chờ dịch: ${translationQueue.size} • Chờ đọc: ${pending.get()}"
                    if(speechQueue.size>=160) { finish("Đã dừng: đọc không theo kịp, tồn quá nhiều từ. Không tự bỏ nội dung."); return@post }
                    speechQueue.addLast(target); pending.incrementAndGet(); pumpSpeech()
                }
            }
        } catch(_:InterruptedException) { /* normal stop */ }
        catch(e:Exception) { if(active) main.post { finish("Đã dừng: lỗi dịch offline ${e.cause?.message ?: e.message}") } }
        finally { translator?.close() }
    }
    private fun pumpSpeech() {
        if(!active || speaking || speechQueue.isEmpty()) return
        if(headphones()==null) { finish("Đã dừng: không còn tai nghe."); return }
        val value=speechQueue.removeFirst()
        if(value.isBlank()) { pending.decrementAndGet(); pumpSpeech(); return }
        val rate=(SessionState.rate + pending.get().coerceAtMost(15)*0.025f).coerceAtMost(2f)
        tts?.setSpeechRate(rate)
        speaking=true; speakingId=(++utterance).toString(); speakStarted=SystemClock.elapsedRealtime()
        if(tts?.speak(value,TextToSpeech.QUEUE_FLUSH,Bundle(),speakingId)!=TextToSpeech.SUCCESS) finish("Đã dừng: không phát được giọng Việt.")
    }
    private fun completeSpeech(id:String?) {
        if(!active || id!=speakingId) return
        speaking=false; pending.decrementAndGet(); pumpSpeech()
    }
    private fun isHeadphone(d:AudioDeviceInfo)=d.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET)
    private fun headphones()=audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isHeadphone(it) }
    private fun notification(message:String):Notification {
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,2,Intent(this,ListenService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setSmallIcon(com.ngheviet.app.R.drawable.ic_listen).setContentTitle("Nghe Việt • Anh → Việt")
            .setContentText(message).setContentIntent(open).setOngoing(true).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null,"Dừng",stop).build()).build()
    }
    private fun setStatus(message:String) {
        SessionState.status=message
        if(!closing) getSystemService(NotificationManager::class.java).notify(1,notification(message))
    }
    private fun finish(message:String) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { finish(message) }; return }
        if(closing) return
        SessionState.status=message; cleanup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun cleanup() {
        if(closing) return
        closing=true; active=false; initializing=false; SessionState.running=false
        main.removeCallbacksAndMessages(null)
        tts?.stop(); tts?.shutdown(); tts=null
        speechQueue.clear(); pending.set(0); translationQueue.clear()
        try { recorder?.stop() } catch(_:Exception) {}
        worker?.interrupt()
        if(worker==null) translator?.close()
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        if(audioCallbackRegistered) audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if(registered) unregisterReceiver(noisy)
        if(wake?.isHeld==true) wake?.release()
    }
    override fun onDestroy() { cleanup(); super.onDestroy() }
}
