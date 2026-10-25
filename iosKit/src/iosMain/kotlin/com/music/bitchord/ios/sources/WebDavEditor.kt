package com.music.bitchord.ios.sources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.ios.IosSettings
import com.music.bitchord.ios.IosWebDav
import com.music.bitchord.ios.tr
import kotlinx.coroutines.launch

/**
 * The phone's WebDAV card: the server's folder, the account and its password. Test asks the
 * server about the folder; Save asks the same, and keeps the server only when it answers, so a
 * typo never becomes a library that is empty for no reason anyone can see.
 */
@Composable
internal fun WebDavEditor(onDismiss: () -> Unit) {
    val connected = IosWebDav.configured.value
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(IosSettings.webdavUrl.flow.value) }
    var username by remember { mutableStateOf(IosSettings.webdavUsername.flow.value) }
    var password by remember { mutableStateOf(IosSettings.webdavPassword.flow.value) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun run(thenSave: Boolean) {
        busy = true
        status = null
        scope.launch {
            val result = IosWebDav.test(url, username, password)
            busy = false
            status = result.fold(
                onSuccess = { tr("Connected") to true },
                onFailure = { tr("Couldn’t reach it: {0}", it.message ?: tr("Failed")) to false },
            )
            if (thenSave && result.isSuccess) {
                IosWebDav.save(url, username, password)
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV") },
        text = {
            Column {
                Text(
                    tr("Point BitChord at a WebDAV music folder — your Nextcloud Music directory or any server speaking WebDAV."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = url,
                    // A result describes what it was run against, so an edit clears it.
                    onValueChange = { url = it; status = null },
                    label = { Text(tr("Server URL")) },
                    placeholder = { Text("https://cloud.example.com/remote.php/dav/files/user/Music") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; status = null },
                    label = { Text(tr("Username")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; status = null },
                    label = { Text(tr("Password")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Testing…"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                status?.let { (text, good) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (good) Color(0xFF34C759) else Color(0xFFFF6B6B),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { run(thenSave = false) }, enabled = !busy && url.isNotBlank()) { Text(tr("Test")) }
                TextButton(onClick = { run(thenSave = true) }, enabled = !busy && url.isNotBlank()) { Text(tr("Save")) }
            }
        },
        dismissButton = {
            Row {
                if (connected) {
                    TextButton(
                        onClick = {
                            IosWebDav.disconnect()
                            onDismiss()
                        },
                        enabled = !busy,
                    ) { Text(tr("Disconnect"), color = Color(0xFFFF6B6B)) }
                }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    )
}
