package com.p0lymatic.lynk.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.p0lymatic.lynk.R
import com.p0lymatic.lynk.data.Service
import com.p0lymatic.lynk.ssh.ConnectionManager
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The browser half of Lynk: every page is served from a local port that only exists while an
 * SSH forward is open. When the screen goes away, so does the forward.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    app: AppState,
    nav: Navigator,
    serverId: String,
    serviceId: String?,
    port: Int,
    scheme: String,
    path: String,
    title: String,
    host: String,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val server = app.store.server(serverId)

    var forward by remember { mutableStateOf<ConnectionManager.LocalForward?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    var pageTitle by remember { mutableStateOf("") }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    var capturedIcon by remember { mutableStateOf<Bitmap?>(null) }
    var sslBypassed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var showSave by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    val initialTarget = "${if (host.isBlank()) "127.0.0.1" else host}:$port"
    var address by remember(serverId, port, scheme, path, host) { mutableStateOf("$initialTarget$path") }

    // Open (or reuse) the SSH forward for the lifetime of this screen.
    LaunchedEffect(serverId, port, reloadKey) {
        val node = server ?: run {
            loadError = "Узел не найден"
            return@LaunchedEffect
        }
        loadError = null
        forward?.let { app.connections.closeForward(it) }
        forward = null
        val opened = withContext(Dispatchers.IO) {
            app.openService(
                Service(
                    id = serviceId ?: "adhoc-$port",
                    serverId = serverId,
                    name = title,
                    host = host.ifBlank { "127.0.0.1" },
                    port = port,
                    scheme = scheme,
                    path = path,
                ),
            )
        }
        if (opened == null) {
            loadError = app.connections.connState(serverId).error
                ?: "Не удалось открыть туннель к $initialTarget"
        }
        forward = opened
    }

    DisposableEffect(Unit) {
        onDispose {
            forward?.let { app.connections.closeForward(it) }
        }
    }

    val url = forward?.let { "${scheme.lowercase()}://127.0.0.1:${it.localPort}${normalizePath(path)}" }
    val loading = progress in 1..99
    val contentAlpha by animateFloatAsState(
        targetValue = if (loading) 0.35f else 1f,
        animationSpec = tween(220),
        label = "pageAlpha",
    )

    BackHandler {
        val wv = webViewHolder[0]
        if (wv != null && wv.canGoBack()) wv.goBack() else nav.pop()
    }

    // Load whenever the tunnel address changes (first open or a manual retry).
    LaunchedEffect(url) {
        url?.let { target -> webViewHolder[0]?.loadUrl(target) }
    }

    fun submitAddress() {
        val parsed = parseAddressInput(address) ?: run {
            app.notify("Укажи порт, например 3000")
            return
        }
        nav.push(
            Route.Browser(
                serverId = serverId,
                serviceId = null,
                port = parsed.port,
                scheme = parsed.scheme,
                path = parsed.path,
                title = "Порт ${parsed.port}",
                host = parsed.host,
            ),
        )
    }

    Column(Modifier.fillMaxSize().background(LynkColors.bg)) {
        BrowserToolbar(
            address = address,
            onAddressChange = { address = it },
            onSubmit = { focus.clearFocus(); submitAddress() },
            addressPrefix = server?.name,
            leading = {
                IconButton(onClick = { nav.pop() }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tab_home),
                        contentDescription = "На старт-экран",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            actions = {
                if (serviceId == null) {
                    IconButton(onClick = { showSave = true }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Сохранить сервис",
                            tint = LynkColors.textDim,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = { reloadKey++ }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Обновить",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box {
                    IconButton(onClick = { overflowOpen = true }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Ещё",
                            tint = LynkColors.textDim,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                        containerColor = LynkColors.surfaceHigh,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Открыть в системном браузере", fontSize = 13.sp, color = LynkColors.text) },
                            onClick = {
                                overflowOpen = false
                                url?.let {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Копировать адрес", fontSize = 13.sp, color = LynkColors.text) },
                            onClick = {
                                overflowOpen = false
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Lynk", url ?: address))
                                app.notify("Адрес скопирован")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Обновить", fontSize = 13.sp, color = LynkColors.text) },
                            onClick = {
                                overflowOpen = false
                                reloadKey++
                            },
                        )
                    }
                }
            },
        )

        Box(Modifier.fillMaxWidth().height(1.5.dp)) {
            if (loading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = LynkColors.textDim,
                    trackColor = Color.Transparent,
                )
            }
        }

        if (sslBypassed) {
            Text(
                text = "Сертификат сервиса не проверяется — Lynk подключён к нему по SSH",
                color = LynkColors.warn,
                fontSize = 10.5.sp,
                fontFamily = Mono,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.fillMaxSize()) {
            if (url != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().alpha(contentAlpha),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(0xFF0F0F11.toInt())
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                builtInZoomControls = true
                                displayZoomControls = false
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                                userAgentString = "$userAgentString Lynk/0.1"
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    loadError = null
                                    progress = 1
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    progress = 100
                                    view?.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        progress = 100
                                        loadError = "Сервис не ответил на $initialTarget. Проверь, что он запущен на узле."
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: android.net.http.SslError?,
                                ) {
                                    sslBypassed = true
                                    handler?.proceed()
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean = false
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                                    if (icon != null) {
                                        capturedIcon = icon
                                        if (serviceId != null) {
                                            app.scope.launch(Dispatchers.IO) {
                                                saveIcon(app, serviceId, icon)
                                            }
                                        }
                                    }
                                }
                            }
                            webViewHolder[0] = this
                        }
                    },
                )
            }

            if (loadError != null) {
                Column(
                    modifier = Modifier.fillMaxSize().background(LynkColors.bg).padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Не открылось", fontSize = 16.sp, color = LynkColors.textDim)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        loadError!!,
                        fontSize = 12.5.sp,
                        color = LynkColors.textMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    GhostButton("Повторить", onClick = { reloadKey++ })
                }
            }
        }
    }

    if (showSave) {
        SaveServiceDialog(
            app = app,
            serverId = serverId,
            host = host.ifBlank { "127.0.0.1" },
            port = port,
            scheme = scheme,
            path = path,
            suggestedName = (pageTitle.ifBlank { title }).take(40),
            icon = capturedIcon,
            onDismiss = { showSave = false },
            onSaved = {
                showSave = false
                app.notify("Сервис сохранён")
            },
        )
    }
}

@Composable
private fun SaveServiceDialog(
    app: AppState,
    serverId: String,
    host: String,
    port: Int,
    scheme: String,
    path: String,
    suggestedName: String,
    icon: Bitmap?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LynkColors.surfaceHigh,
        titleContentColor = LynkColors.text,
        title = { Text("Сохранить сервис", fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "$host:$port · $scheme",
                    fontFamily = Mono,
                    fontSize = 11.5.sp,
                    color = LynkColors.textMuted,
                )
                Spacer(Modifier.height(12.dp))
                LynkField(name, { name = it }, label = "Название", placeholder = "Панель")
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val id = UUID.randomUUID().toString()
                    app.store.upsertService(
                        Service(
                            id = id,
                            serverId = serverId,
                            name = name.ifBlank { "Порт $port" },
                            host = host,
                            port = port,
                            scheme = scheme,
                            path = normalizePath(path),
                            favorite = true,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    if (icon != null) {
                        app.scope.launch(Dispatchers.IO) { saveIcon(app, id, icon) }
                    }
                    onSaved()
                },
            ) { Text("Сохранить", color = LynkColors.text) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Отмена", color = LynkColors.textDim)
            }
        },
    )
}

private fun saveIcon(app: AppState, serviceId: String, icon: Bitmap) {
    runCatching {
        val scaled = Bitmap.createScaledBitmap(icon, 96, 96, true)
        app.store.iconFile(serviceId).outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        app.store.bumpIcon(serviceId)
    }
}

private fun normalizePath(path: String): String =
    if (path.isBlank()) "/" else if (path.startsWith("/")) path else "/$path"
