# Lynk — контракт для рабочих

Android-приложение: менеджер SSH-туннелей со встроенным терминалом и браузером.
Подключаешься к узлу — открываешь его сервисы по портам, не выставляя их в интернет.

## Стек и правила

- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1, compileSdk 35, minSdk 26, Jetpack Compose (BOM 2024.12.01), Material 3.
- Пакет `com.p0lymatic.lynk`. Корень проекта: `/home/aleph/Lynk`.
- SSH: `com.github.mwiede:jsch:0.2.20`. Сериализация: kotlinx-serialization-json 1.7.3.
- **Никаких новых зависимостей** — только то, что уже в `app/build.gradle.kts`.
- **Не запускать gradle** (родительский агент собирает проект сам, параллельная сборка ломает lock).
- **Не выполнять git-команды** (коммитит и пушит родительский агент).
- Пользовательские строки — на русском, технические значения моноширинным шрифтом.
- Стиль UI: строгий серый, тёмная тема, цвета только из `LynkColors`. Никаких `Color.Red` и прочих литералов, кроме статусов из `LynkColors`.
- Не использовать экспериментальные API Compose без `@OptIn(ExperimentalMaterial3Api::class)`.
- Иконки: доступны только `Icons.Filled.*` из `material-icons-core` (Add, ArrowBack, Close, Refresh, Delete, Edit, Settings, Lock, PlayArrow, Check, Search, Info, Warning, Star, MoreVert, KeyboardArrowDown, List, Home, Menu, Clear, Done, Share, Send, Person, Phone, Place, Email, DateRange, Build, Create, ExitToApp, Face, Favorite, FavoriteBorder, LocationOn, MailOutline, Notifications, ShoppingCart, ThumbUp, CheckCircle, Call).
  Для остального рисуй текст-глиф (например `>_`) или собственный vector drawable в `res/drawable`.
- Комментарии в коде — минимально, по-английски, только там где неочевидно.

## Уже существует и менять НЕЛЬЗЯ

- `data/Models.kt` — `ServerConfig`, `Service`, `Tunnel`, `HostKeyEntry`, `AppData`, `AuthType{PASSWORD,KEY}`, `UnknownHostKeyException`, `ChangedHostKeyException`.
- `data/Store.kt` — `Store(context)`; `val state: StateFlow<AppData>`; `server(id)`, `upsertServer`, `deleteServer`, `service(id)`, `servicesFor(serverId)`, `upsertService`, `deleteService`, `bumpIcon(serviceId)`, `iconFile(serviceId): File`, `tunnel(id)`, `upsertTunnel`, `deleteTunnel`, `hostKey(host,port)`, `trustHostKey`, `forgetHostKey`, `putSecret(serverId, SecretKind, plain)`, `getSecret(serverId, SecretKind)`, `hasSecret`, `clearSecrets`, `enum SecretKind{PASSWORD,KEY,PASSPHRASE}`.
- `data/SecureVault.kt` — шифрование секретов через Android Keystore.
- `ssh/HostKeys.kt` — проверка отпечатков ключей сервера.
- `ssh/ConnectionManager.kt` — `class ConnectionManager(context, store)`:
  - `enum Phase {IDLE, CONNECTING, CONNECTED, ERROR}`; `data class ConnState(phase, error: String?, since: Long)` c `val connected: Boolean`.
  - `val states: StateFlow<Map<String, ConnState>>`, `val forwards: StateFlow<Map<String, LocalForward>>`, `val tunnelErrors: StateFlow<Map<String,String>>`, `val terminals: StateFlow<Int>`.
  - `data class LocalForward(id, serverId, localPort, remoteHost, remotePort, tunnelId: String?, label: String)`.
  - `suspend ensureConnected(server): Session`, `connState(id)`, `isConnected(id)`, `activeCount()`;
    `suspend disconnect(id)`, `suspend disconnectAll()`, `fun trustHostKey(entry)`;
    `suspend openForward(server, remoteHost, remotePort, localPort=0, tunnelId=null, label=""): LocalForward`;
    `closeForward(forward)`, `closeForwards(predicate)`, `setTunnelError(tunnelId, message?)`;
    `suspend openTerminal(server, cols, rows): TerminalSession`;
    `companion { fun describe(t: Throwable): String; const val CONNECT_TIMEOUT_MS }`.
- `ui/AppState.kt` — `class AppState(context, store, connections)`:
  - `val scope: CoroutineScope` (Main), `val messages: SharedFlow<String>`, `val lastServerId: String?`,
    `var pendingHostKey: PendingHostKey?` (+ `acceptPendingHostKey()`, `dismissPendingHostKey()`),
    `fun notify(text)`, `suspend connect(server, retry = {}): Boolean`, `fun disconnect(serverId)`,
    `fun serverOf(id): ServerConfig?`, `suspend openService(service, localPort=0): LocalForward?`,
    `suspend openPort(server, host="127.0.0.1", port, ...): LocalForward?`,
    `isTunnelActive(id)`, `tunnelForward(id)`, `startTunnel(tunnel)`, `stopTunnel(tunnel)`.
- `ui/Nav.kt` — `enum TabId{HOME,SERVERS,TUNNELS,SETTINGS}`, `sealed interface Route { Tab, ServerDetail(serverId), ServerEdit(serverId?), ServiceEdit(serviceId?, serverId), TunnelEdit(tunnelId?, serverId), Terminal(serverId), Browser(serverId, serviceId?, port, scheme, path, title, host) }`, `class Navigator { current, canPop, push(route), pop(), popToRoot(), selectTab(tab) }`.
- `ui/theme/Theme.kt` — `LynkColors{bg,surface,surfaceHigh,border,borderSoft,text,textDim,textMuted,accent,ok,warn,err,termBg}`, `Mono` (FontFamily.Monospace), `LynkTheme{}`.
- `ui/Components.kt` — `SectionLabel(text, modifier, trailing)`, `LynkCard(modifier, onClick, contentPadding, content: ColumnScope.() -> Unit)`, `LynkCardBorder(modifier, content)`, `PrimaryButton(text, onClick, modifier, enabled, loading)`, `GhostButton(text, onClick, modifier, enabled, danger, icon: ImageVector?)`, `LynkField(value, onValueChange, label, modifier, placeholder, mono, keyboardType, singleLine, minLines, maxLines, isPassword, trailing)`, `ScreenHeader(title, subtitle, onBack, actions: RowScope.() -> Unit)`, `StatusDot(phase, size)`, `StatusLine(phase, text)`, `MonoBadge(text, modifier)`, `KeyRow(label, value, mono, valueColor)`, `EmptyState(title, text, actionText, onAction)`, `ConfirmDialog(title, text, confirmText, danger, onConfirm, onDismiss)`, `DividerLine(modifier)`, `SectionNote(text, modifier)`, `EditorColumn(content: ColumnScope.() -> Unit)`.
- `service/LynkService.kt` — `class LynkService : Service()` c `companion { fun sync(context: Context, active: Int): Boolean }`.
- `util/FaviconStore.kt`, `util/Formatters.kt` — утилиты (могут создаваться параллельно).
- `LynkApp.kt`, `MainActivity.kt` — точка входа.

## Сигнатуры экранов (обязательны, их вызывает `ui/Root.kt`)

```kotlin
@Composable fun HomeScreen(app: AppState, nav: Navigator)
@Composable fun ServersScreen(app: AppState, nav: Navigator)
@Composable fun ServerDetailScreen(app: AppState, nav: Navigator, serverId: String)
@Composable fun ServerEditScreen(app: AppState, nav: Navigator, serverId: String?)
@Composable fun ServiceEditScreen(app: AppState, nav: Navigator, serviceId: String?, serverId: String)
@Composable fun TunnelsScreen(app: AppState, nav: Navigator)
@Composable fun TunnelEditScreen(app: AppState, nav: Navigator, tunnelId: String?, serverId: String)
@Composable fun SettingsScreen(app: AppState, nav: Navigator)
@Composable fun TerminalScreen(app: AppState, nav: Navigator, serverId: String)
@Composable fun BrowserScreen(app: AppState, nav: Navigator, serverId: String, serviceId: String?, port: Int, scheme: String, path: String, title: String, host: String)
```

Правила навигации: переход — `nav.push(Route.X)`, назад — `nav.pop()`; экран без собственной кнопки возврата в шапке считается «встроенным». Данные читать так:

```kotlin
val data by app.store.state.collectAsStateWithLifecycle()
val connStates by app.connections.states.collectAsStateWithLifecycle()
```

`collectAsStateWithLifecycle` — из `androidx.lifecycle.compose`.

Новые сущности создавать так:

```kotlin
val id = UUID.randomUUID().toString()
app.store.upsertServer(ServerConfig(id = id, name = name, host = host, port = port, user = user, auth = auth, createdAt = System.currentTimeMillis()))
app.store.putSecret(id, Store.SecretKind.PASSWORD, password)
```

## Идентификаторы, которые нельзя переименовывать

- Файловые: `HomeScreen.kt`, `ServersScreen.kt`, `ServerDetailScreen.kt`, `ServerEditScreen.kt`, `ServiceEditScreen.kt`, `TunnelsScreen.kt`, `TunnelEditScreen.kt`, `SettingsScreen.kt`, `TerminalScreen.kt`, `BrowserScreen.kt` в пакете `com.p0lymatic.lynk.ui`.
- Логика терминала: `ssh/TerminalScreen.kt` (класс `TerminalScreen`, эмулятор), `ssh/TerminalSession.kt` (класс `TerminalSession`).
  Конструктор: `TerminalSession(session: com.jcraft.jsch.Session, cols: Int, rows: Int, onClosed: () -> Unit)`.
