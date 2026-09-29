package com.minimal.zipextractor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val externalUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureIntent(intent)
        setContent {
            MaterialTheme(colorScheme = DiscordColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ArchiveExtractorScreen(external = externalUris) { externalUris.value = emptyList() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureIntent(intent)
    }

    private fun captureIntent(intent: Intent?) {
        intent ?: return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        }
        if (uri != null) externalUris.value = listOf(uri)
    }
}

private const val SUPPORTED_LABEL =
    "zip · 7z · rar/rar5 · tar(.gz/.bz2/.xz/.Z) · gz/bz2/xz/lzma/Z/br · cpio · ar/deb"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveExtractorScreen(external: State<List<Uri>>, onExternalConsumed: () -> Unit) {
    val context = LocalContext.current
    var queue by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentName by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ArchiveKind.UNKNOWN) }
    var entries by remember { mutableStateOf<List<ArchiveEntry>>(emptyList()) }
    var password by remember { mutableStateOf("") }
    var intoSubfolder by remember { mutableStateOf(true) }
    var overwrite by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Pick or share an archive to begin") }
    var progress by remember { mutableStateOf(0f) }
    var skippedTotal by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val cancelFlag = remember { AtomicBoolean(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }

    fun clearCache() {
        previewFile?.delete()
        previewFile = null
    }

    fun loadPreview(uri: Uri, pass: CharArray?) {
        isBusy = true
        entries = emptyList()
        status = "Reading archive…"
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { copyUriToCache(context, uri) { cancelFlag.get() } }
                clearCache()
                previewFile = file
                currentName = queryDisplayName(context, uri) ?: "archive"
                val detected = withContext(Dispatchers.IO) { Archives.detect(file, currentName) }
                kind = detected
                if (!Archives.isSupported(detected)) {
                    status = "Unrecognised format"
                    return@launch
                }
                val list = withContext(Dispatchers.IO) {
                    val acc = ArrayList<ArchiveEntry>()
                    Archives.read(file, detected, pass, { cancelFlag.get() }) { entry, _ -> acc.add(entry) }
                    acc
                }
                entries = list
                status = "${list.count { !it.isDirectory }} files, ${list.count { it.isDirectory }} folders"
            } catch (e: Exception) {
                status = "Could not read: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                withContext(Dispatchers.Main) { isBusy = false }
            }
        }
    }

    // Incoming VIEW/SEND intents
    LaunchedEffect(external.value) {
        val incoming = external.value
        if (incoming.isNotEmpty()) {
            queue = incoming
            loadPreview(incoming.first(), password.toCharArray())
            onExternalConsumed()
        }
    }

    val archivePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            queue = uris
            if (uris.size > 1) status = "${uris.size} archives queued"
            loadPreview(uris.first(), password.toCharArray())
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null && queue.isNotEmpty()) {
            cancelFlag.set(false)
            isBusy = true
            progress = 0f
            skippedTotal = 0
            status = "Extracting…"
            job = scope.launch {
                var totalExtracted = 0
                var totalSkipped = 0
                try {
                    queue.forEachIndexed { index, uri ->
                        if (cancelFlag.get()) return@forEachIndexed
                        val file = withContext(Dispatchers.IO) { copyUriToCache(context, uri) { cancelFlag.get() } }
                        val name = queryDisplayName(context, uri) ?: "archive_$index"
                        val detected = withContext(Dispatchers.IO) { Archives.detect(file, name) }
                        val count = withContext(Dispatchers.IO) {
                            extractArchive(context, file, detected, name, password.toCharArray(),
                                treeUri, intoSubfolder, overwrite, cancelFlag::get) { p ->
                                scope.launch(Dispatchers.Main) { progress = p }
                            }
                        }
                        file.delete()
                        totalExtracted += count.first
                        totalSkipped += count.second
                        skippedTotal = totalSkipped
                        withContext(Dispatchers.Main) {
                            status = "Archive ${index + 1}/${queue.size} — $totalExtracted files"
                        }
                    }
                    val done = if (cancelFlag.get()) "Cancelled" else "Done"
                    status = "$done — $totalExtracted file(s)" +
                        if (totalSkipped > 0) ", $totalSkipped unsafe entry(ies) skipped" else ""
                    Toast.makeText(context, "$done — $totalExtracted files", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    status = "Error: ${e.message ?: e.javaClass.simpleName}"
                    Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    withContext(Dispatchers.Main) { isBusy = false; job = null }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text("Clean Zip", style = MaterialTheme.typography.headlineMedium)
        Text(SUPPORTED_LABEL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = { archivePicker.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy
        ) { Text("Select archives") }

        if (queue.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (queue.size > 1) "${queue.size} archives — showing $currentName" else currentName,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (if encrypted)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy
            )
            TextButton(
                onClick = { queue.firstOrNull()?.let { loadPreview(it, password.toCharArray()) } },
                enabled = !isBusy && password.isNotEmpty()
            ) { Text("Re-read with password") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = intoSubfolder, onCheckedChange = { intoSubfolder = it }, enabled = !isBusy)
                Text("Extract into a folder per archive", fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = overwrite, onCheckedChange = { overwrite = it }, enabled = !isBusy)
                Text("Overwrite existing files", fontSize = 13.sp)
            }

            if (entries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Contents:", style = MaterialTheme.typography.labelMedium)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(entries.take(50)) { entry ->
                        Text(
                            "• ${if (entry.isDirectory) entry.name + "/" else entry.name}",
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    if (entries.size > 50) item { Text("… and ${entries.size - 50} more", fontSize = 11.sp) }
                }
            }

            Spacer(Modifier.height(12.dp))
            if (isBusy) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { cancelFlag.set(true); status = "Cancelling…" },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel") }
            } else {
                Button(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = entries.any { !it.isDirectory } || queue.size > 1
                ) { Text("Choose folder & extract") }
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            "Everything runs on-device. No permissions. Your files never leave the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

private fun copyUriToCache(context: Context, uri: Uri, cancelled: () -> Boolean): File {
    val name = queryDisplayName(context, uri) ?: "archive"
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val out = File(context.cacheDir, "import_${System.currentTimeMillis()}_$safe")
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                if (cancelled()) break
                val r = input.read(buf)
                if (r < 0) break
                output.write(buf, 0, r)
            }
        }
    } ?: throw IllegalStateException("Cannot open selected file")
    return out
}

private fun baseName(name: String): String = name.substringBeforeLast('.', name).ifBlank { name }

private fun extractArchive(
    context: Context,
    file: File,
    kind: ArchiveKind,
    archiveName: String,
    password: CharArray,
    destTreeUri: Uri,
    intoSubfolder: Boolean,
    overwrite: Boolean,
    cancelled: () -> Boolean,
    onProgress: (Float) -> Unit
): Pair<Int, Int> {
    val root = DocumentFile.fromTreeUri(context, destTreeUri)
        ?: throw IllegalStateException("Cannot access destination folder")
    val dest = if (intoSubfolder) {
        val folder = baseName(archiveName)
        root.findFile(folder)?.takeIf { it.isDirectory } ?: root.createDirectory(folder) ?: root
    } else root
    val pass = password.takeIf { it.isNotEmpty() }

    var totalFiles = 0
    var totalBytes = 0L
    Archives.read(file, kind, pass, cancelled) { entry, _ ->
        if (!entry.isDirectory) {
            totalFiles++
            if (entry.size > 0) totalBytes += entry.size
        }
    }
    if (totalFiles == 0) return 0 to 0

    var doneBytes = 0L
    var doneFiles = 0
    var extracted = 0
    var skipped = 0

    Archives.read(file, kind, pass, cancelled) { entry, source ->
        if (cancelled()) return@read
        val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        val unsafe = parts.isEmpty() || parts.any { it == ".." }
        if (unsafe) {
            if (!entry.isDirectory) skipped++
        } else {
            var dir = dest
            for (i in 0 until parts.size - 1) {
                val existing = dir.findFile(parts[i])
                dir = if (existing != null && existing.isDirectory) existing
                else dir.createDirectory(parts[i]) ?: dir
            }
            if (entry.isDirectory) {
                if (dir.findFile(parts.last()) == null) dir.createDirectory(parts.last())
            } else if (source != null) {
                val fileName = parts.last()
                if (overwrite) dir.findFile(fileName)?.delete()
                val target = dir.createFile("application/octet-stream", fileName)
                    ?: dir.createFile("application/octet-stream", "extracted_${System.currentTimeMillis()}_$fileName")
                if (target != null) {
                    context.contentResolver.openOutputStream(target.uri)?.use { raw ->
                        val out = CountingOutputStream(raw) { doneBytes += it }
                        source.writeTo(out)
                        out.flush()
                    }
                    extracted++
                }
            }
        }
        doneFiles++
        val p = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else doneFiles.toFloat() / totalFiles
        onProgress(p.coerceIn(0f, 1f))
    }
    return extracted to skipped
}

private class CountingOutputStream(
    private val delegate: OutputStream,
    private val onBytes: (Long) -> Unit
) : OutputStream() {
    override fun write(b: Int) { delegate.write(b); onBytes(1) }
    override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len); onBytes(len.toLong()) }
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
