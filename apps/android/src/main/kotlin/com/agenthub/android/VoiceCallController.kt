package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID

/** Foreground, turn-by-turn voice conversation. Never retries a submitted model input. */
class VoiceCallController(private val context: Context, private val session: MobileSession) {
    enum class Phase { IDLE, LISTENING, WAITING, SPEAKING, PAUSED }
    var phase by mutableStateOf(Phase.IDLE); private set
    var transcript by mutableStateOf(""); private set
    var status by mutableStateOf(""); private set
    val active get() = phase != Phase.IDLE
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var speechReady = false
    private var target = ""
    private var operation: String? = null
    private var startedAt = 0L
    private var generation = 0
    private var poll: Job? = null
    private var silenceRetries = 0

    init {
        speaker = TextToSpeech(context.applicationContext) { code ->
            speechReady = code == TextToSpeech.SUCCESS
            if(speechReady) {
                val language = speaker?.setLanguage(Locale.getDefault())
                speechReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        speaker?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { tasks.launch {
                if(id == "voice-$generation-end" && phase == Phase.SPEAKING) listen()
            } }
            @Deprecated("Platform callback")
            override fun onError(id: String?) { tasks.launch {
                if(id?.startsWith("voice-$generation-") == true && phase == Phase.SPEAKING)
                    pause("语音播放失败，文字回复已保留。点继续重开麦克风。")
            } }
        })
    }
    fun start() {
        if(!session.canStartVoiceCall) { status = "请先连接电脑并打开一个空闲对话"; return }
        if(session.voice?.active == true) { status = "请先结束当前语音输入"; return }
        target = session.targetKey; operation = null; transcript = ""; silenceRetries = 0
        poll?.cancel()
        listen()
        poll = tasks.launch { while(active) { update(); delay(400) } }
    }
    private fun current() = session.targetKey == target && session.isForeground &&
        session.selectedClient.online && session.selectedThread.isNotEmpty()
    private fun listen() {
        if(!current()) { pause("连接或对话已变化，语音已暂停"); return }
        if(!SpeechRecognizer.isRecognitionAvailable(context)) {
            pause("此手机没有可用的系统语音识别服务，请安装或启用系统语音服务"); return
        }
        generation++; val token = generation
        speaker?.stop(); recognizer?.destroy()
        transcript = ""; phase = Phase.LISTENING; status = "正在听你说话…"
        val next = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        recognizer = next
        next.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if(token == generation) status = "正在识别…" }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(results: Bundle?) {
                if(token == generation && phase == Phase.LISTENING)
                    transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onResults(results: Bundle?) {
                if(token != generation || phase != Phase.LISTENING) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                next.destroy(); recognizer = null
                if(!current()) { pause("对话目标已变化，语音未发送"); return }
                if(text.isEmpty()) { pause("没有识别到内容，点继续重开麦克风"); return }
                transcript = text; phase = Phase.WAITING; status = "正在提交语音指令…"
                val id = UUID.randomUUID().toString()
                operation = id; startedAt = System.currentTimeMillis()
                if(!session.sendVoiceCall(text, id)) {
                    operation = null
                    pause("本句未提交，识别文字仍在此处。请先结束通话检查连接或共享许可，再重新说话。")
                }
            }
            override fun onError(error: Int) {
                if(token != generation || phase != Phase.LISTENING) return
                recognizer?.destroy(); recognizer = null
                if(error in listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                    silenceRetries++ < 2 && current()) {
                    tasks.launch { delay(500); if(token == generation && phase == Phase.LISTENING) listen() }
                } else pause(when(error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限未开启，请在手机设置中允许"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音识别网络不可用，点继续重试录音"
                    else -> "听写已暂停（$error），点继续重开麦克风"
                })
            }
        })
        try {
            next.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        } catch(_: Exception) { pause("无法打开系统语音服务，请检查麦克风权限") }
    }
    private fun update() {
        if(!active || phase == Phase.PAUSED) return
        if(!current()) { pause("已离开原对话或电脑断连，语音已暂停"); return }
        if(phase != Phase.WAITING) return
        val id = operation ?: return
        val receipt = session.runtime?.receipts?.lastOrNull { it.operationId == id }
        if(receipt?.state in listOf("failed", "rejected", "outcome-unknown", "interrupted", "superseded")) {
            pause(receipt?.error ?: "本次输入未完成，请在原对话查看回执。不会自动重发。"); return
        }
        if(session.runtime?.approvals?.isNotEmpty() == true) {
            pause("电脑任务正在等待审批，请先在对话中处理"); return
        }
        if(receipt == null) {
            if(System.currentTimeMillis() - startedAt > 60_000)
                pause("发送结果尚未确认，请查看原操作记录。不会自动重发。")
            return
        }
        val run = receipt.turnId ?: return
        val replies = session.messages.takeLast(100).filter {
            it.role == "assistant" && it.runId == run && it.kind == null && it.replyPhase != "commentary"
        }
        if(replies.isEmpty() || session.running) { status = "Codex 正在执行，等待本次回复…"; return }
        if(session.runtimeState in listOf("failed", "interrupted")) { pause("任务已结束，请查看文字结果"); return }
        val text = replies.joinToString("\n") { it.text }.trim()
        if(text.isNotEmpty()) speak(text)
    }
    private fun speak(text: String) {
        operation = null
        if(!speechReady) { pause("系统语音播放尚未就绪，文字回复已显示"); return }
        generation++; val token = generation; silenceRetries = 0
        phase = Phase.SPEAKING; status = "正在朗读回复 · 可打断后继续说"
        val plain = text.replace(Regex("```[\\s\\S]*?```"), " 代码内容请查看屏幕。 ")
            .replace(Regex("[#*_`>]"), "").take(12_000)
        val size = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(100)
        val chunks = plain.chunked(size)
        chunks.forEachIndexed { index, chunk ->
            val id = if(index == chunks.lastIndex) "voice-$token-end" else "voice-$token-$index"
            val result = speaker?.speak(chunk, if(index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            if(result != TextToSpeech.SUCCESS) { pause("语音播放失败，文字回复已保留"); return }
        }
    }
    fun interruptAndListen() {
        if(!active || !current()) { stop(); return }
        if(operation != null && phase == Phase.PAUSED) {
            phase = Phase.WAITING; status = "继续查询原操作，不重新发送"; return
        }
        if(phase == Phase.WAITING) { status = "电脑仍在执行；需要中断时请使用“停止电脑任务”"; return }
        operation = null; silenceRetries = 0; listen()
    }
    private fun pause(message: String) {
        generation++; recognizer?.destroy(); recognizer = null; speaker?.stop()
        phase = Phase.PAUSED; status = message
    }
    fun stop() {
        generation++; recognizer?.destroy(); recognizer = null; speaker?.stop()
        poll?.cancel(); poll = null; operation = null; phase = Phase.IDLE
    }
    fun dispose() { stop(); speaker?.shutdown(); speaker = null; tasks.cancel() }
}
