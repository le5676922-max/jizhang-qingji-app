package com.example.jizhang.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.jizhang.data.LedgerRepository
import com.example.jizhang.data.backup.EncryptedBackupCodec
import com.example.jizhang.data.backup.LedgerBackupException
import com.example.jizhang.data.backup.PreparedRestore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Only user-selected SAF files are read/written. Authentication precedes overwrite confirmation. */
@Composable
fun BackupControls(
    repository: LedgerRepository,
    busy: Boolean = false,
    onBusyChanged: (Boolean) -> Unit,
    onRestored: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val blocked by repository.restoreBlocked.collectAsState()
    val storageError by repository.storageError.collectAsState()
    var working by remember { mutableStateOf(false) }
    var passwordMode by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf("") }
    var repeatedPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var prepared by remember { mutableStateOf<PreparedRestore?>(null) }
    val active=working || busy
    fun beginWork() { working=true;onBusyChanged(true) }
    fun endWork() { working=false;onBusyChanged(false) }
    fun clearPassword() { password="";repeatedPassword="";passwordError=null;passwordMode=0 }

    DisposableEffect(repository) {
        onDispose {
            // Detached buffers are owned by an in-flight task until its finally block.
            pendingExport?.fill(0);pendingImport?.fill(0);prepared?.dispose()
            pendingExport=null;pendingImport=null;prepared=null
        }
    }

    val exportPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes=pendingExport
        pendingExport=null
        if(bytes!=null) {
            if(uri==null) bytes.fill(0) else scope.launch(start=CoroutineStart.UNDISPATCHED) {
                beginWork()
                try {
                    coroutineContext.ensureActive()
                    withContext(Dispatchers.IO) {
                        val stream=context.contentResolver.openOutputStream(uri,"wt")
                            ?: error("Selected file cannot be written")
                        stream.use { it.write(bytes);it.flush() }
                    }
                    onMessage("完整加密备份已保存，请妥善保管文件和密码")
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { onMessage("备份文件未完整保存，请删除未完成的文件后重试") }
                finally { bytes.fill(0);endWork() }
            }
        }
    }
    val importPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch(start=CoroutineStart.UNDISPATCHED) {
            beginWork()
            var bytes:ByteArray?=null
            try {
                coroutineContext.ensureActive()
                withContext(NonCancellable) {
                    bytes=withContext(Dispatchers.IO) { readEncryptedBackup(context,uri) }
                }
                coroutineContext.ensureActive()
                pendingImport?.fill(0);pendingImport=bytes;bytes=null
                clearPassword();passwordMode=2
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { onMessage(backupMessage(failure)) }
            finally { bytes?.fill(0);endWork() }
        }
    }

    Card(Modifier.fillMaxWidth().testTag("backup-controls")) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("备份与恢复",style=MaterialTheme.typography.titleMedium)
            Text("备份包含账单、识别来源、分类规则及设置。密码无法找回；恢复后自动识别关闭，需重新开启。",
                style=MaterialTheme.typography.bodySmall)
            if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick={clearPassword();passwordMode=1},enabled=!active && !blocked && pendingExport==null,
                    modifier=Modifier.testTag("create-backup")) { Text("创建加密备份") }
                OutlinedButton(onClick={
                    runCatching { importPicker.launch(arrayOf("application/octet-stream","*/*")) }
                        .onFailure { onMessage("请通过系统文件选择器打开备份") }
                },enabled=!active && !blocked,modifier=Modifier.testTag("open-backup")) { Text("恢复备份") }
            }
            if(blocked) {
                Text(storageError ?: "账本正在准备，采集和修改暂时暂停",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={scope.launch(start=CoroutineStart.UNDISPATCHED) {
                    beginWork()
                    try {
                        withContext(NonCancellable) {
                            repository.retryPendingRestore()
                            withContext(Dispatchers.Main.immediate) { onRestored() }
                        }
                        coroutineContext.ensureActive()
                        onMessage("原账本已安全就绪")
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(failure:Exception) { onMessage(backupMessage(failure)) }
                    finally { endWork() }
                }},enabled=!active,modifier=Modifier.testTag("retry-backup-recovery")) { Text("重试恢复原账本") }
            }
        }
    }

    if(passwordMode!=0) AlertDialog(
        onDismissRequest={if(!active) {pendingImport?.fill(0);pendingImport=null;clearPassword()}},
        title={Text(if(passwordMode==1) "设置备份密码" else "验证备份")},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(passwordMode==1) "使用至少8个字符的密码，忘记密码将无法解密备份。" else "先验证密码与文件完整性，再确认覆盖当前账本。")
            OutlinedTextField(value=password,onValueChange={if(it.length<=1024)password=it;passwordError=null},
                label={Text("备份密码")},singleLine=true,enabled=!active,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                modifier=Modifier.testTag("backup-password"))
            if(passwordMode==1) OutlinedTextField(value=repeatedPassword,
                onValueChange={if(it.length<=1024)repeatedPassword=it;passwordError=null},
                label={Text("再次输入密码")},singleLine=true,enabled=!active,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                modifier=Modifier.testTag("backup-password-repeat"))
            passwordError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},
        dismissButton={TextButton(onClick={pendingImport?.fill(0);pendingImport=null;clearPassword()},enabled=!active) {Text("取消")}},
        confirmButton={TextButton(onClick={
            val mode=passwordMode
            if(password.isEmpty() || (mode==1 && password.length<8)) {passwordError="请输入有效密码，创建备份至少8个字符";return@TextButton}
            if(mode==1 && password!=repeatedPassword) {passwordError="两次密码不一致";return@TextButton}
            val secret=password.toCharArray()
            password="";repeatedPassword=""
            scope.launch(start=CoroutineStart.UNDISPATCHED) {
                beginWork()
                var generated:ByteArray?=null
                var ready:PreparedRestore?=null
                val imported=if(mode==2) pendingImport.also { pendingImport=null } else null
                try {
                    coroutineContext.ensureActive()
                    if(mode==1) {
                        withContext(NonCancellable) {generated=repository.createEncryptedBackup(secret)}
                        coroutineContext.ensureActive()
                        pendingExport?.fill(0);pendingExport=generated;generated=null
                        clearPassword()
                        try { exportPicker.launch("qingji-${SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(Date())}.qjbackup") }
                        catch(_:Exception) {pendingExport?.fill(0);pendingExport=null;onMessage("请通过系统文件选择器保存备份")}
                    } else {
                        requireNotNull(imported)
                        withContext(NonCancellable) {ready=repository.prepareRestore(imported,secret)}
                        coroutineContext.ensureActive()
                        prepared?.dispose();prepared=ready;ready=null
                        clearPassword()
                    }
                } catch(cancelled:CancellationException) {throw cancelled}
                catch(failure:Exception) {clearPassword();onMessage(backupMessage(failure))}
                finally {secret.fill('\u0000');generated?.fill(0);imported?.fill(0);ready?.dispose();endWork()}
            }
        },enabled=!active && !blocked,modifier=Modifier.testTag("backup-password-confirm")) {Text(if(passwordMode==1) "生成备份" else "验证并预览")}},
    )

    prepared?.let { preview -> AlertDialog(
        onDismissRequest={if(!active) {preview.dispose();prepared=null}},
        title={Text("确认恢复备份")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("备份时间：${SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(Date(preview.preview.createdAt))}")
            Text("账目 ${preview.preview.entryCount} 条（含已删除 ${preview.preview.deletedEntryCount} 条），识别来源 ${preview.preview.eventCount} 条")
            Text("将完整替换当前账本。自动识别会关闭；系统通知与无障碍权限不会恢复。请先备份当前数据。")
        }},
        dismissButton={TextButton(onClick={preview.dispose();prepared=null},enabled=!active) {Text("取消")}},
        confirmButton={TextButton(onClick={
            prepared=null
            scope.launch(start=CoroutineStart.UNDISPATCHED) {
                beginWork()
                try {
                    coroutineContext.ensureActive()
                    withContext(NonCancellable) {
                        repository.restorePrepared(preview)
                        withContext(Dispatchers.Main.immediate) { onRestored() }
                    }
                    coroutineContext.ensureActive()
                    onMessage("账本恢复完成，自动识别已关闭")
                } catch(cancelled:CancellationException) {throw cancelled}
                catch(failure:Exception) {onMessage(backupMessage(failure))}
                finally {preview.dispose();endWork()}
            }
        },enabled=!active && !blocked,modifier=Modifier.testTag("confirm-backup-restore")) {Text("覆盖并恢复")}},
    ) }
}

private class BackupFileTooLarge : Exception()

internal fun readEncryptedBackup(context:Context, uri:Uri):ByteArray {
    val input=context.contentResolver.openInputStream(uri) ?: error("File is unavailable")
    return input.use { stream ->
        val bytes=ByteArrayOutputStream()
        val chunk=ByteArray(8192)
        var emptyReads=0
        try {
            while(true) {
                val count=stream.read(chunk)
                if(count<0) break
                if(count==0) { require(++emptyReads<=3);continue }
                emptyReads=0
                if(bytes.size().toLong()+count>EncryptedBackupCodec.MAX_ENCRYPTED_BYTES) throw BackupFileTooLarge()
                bytes.write(chunk,0,count)
            }
            bytes.toByteArray()
        } finally {chunk.fill(0);bytes.close()}
    }
}

private fun backupMessage(failure:Exception):String = when(failure) {
    is LedgerBackupException -> failure.message ?: "备份操作未完成，请重试"
    is BackupFileTooLarge -> "备份文件过大，当前支持约16MB的完整备份"
    else -> "未能完成备份操作，请保留原账本和文件后重试"
}
