package com.agenthub.android

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun VoiceCallAction(session: MobileSession, interactive: Boolean) {
    val context = LocalContext.current
    val call = remember(session) { VoiceCallController(context.applicationContext, session) }
    DisposableEffect(call) { onDispose { call.dispose() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if(allowed) call.start() else session.error = "语音通话需要麦克风权限，请在手机设置中允许"
    }
    IconAction("phone", "语音通话", "voice-call", interactive && session.canStartVoiceCall) {
        permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    if(call.active) AlertDialog(
        onDismissRequest = call::stop,
        title = { Text("语音通话 · ${session.selectedClient.name}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(call.status, Modifier.testTag("voice-call-status"))
            if(call.transcript.isNotBlank()) Text(call.transcript, maxLines = 6)
            Text("说完后自动发送，回复后继续听。播报时可点击打断。结束通话只关闭语音，电脑任务继续。", style = MaterialTheme.typography.bodySmall)
            if(session.canStop) TextButton(onClick = { session.stopTask() }) { Text("停止电脑任务") }
        } },
        confirmButton = { TextButton(onClick = call::interruptAndListen,
            enabled = call.phase != VoiceCallController.Phase.WAITING,
            modifier = Modifier.heightIn(min = 48.dp).testTag("voice-call-interrupt")) {
            Text(if(call.phase == VoiceCallController.Phase.SPEAKING) "打断并说话" else if(call.phase == VoiceCallController.Phase.PAUSED) "继续" else "重新听")
        } },
        dismissButton = { TextButton(onClick = call::stop, modifier = Modifier.heightIn(min = 48.dp)) { Text("结束通话") } }
    )
}
