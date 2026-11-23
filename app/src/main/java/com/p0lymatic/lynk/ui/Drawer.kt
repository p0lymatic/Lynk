package com.p0lymatic.lynk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p0lymatic.lynk.R
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono

private val ItemShape = RoundedCornerShape(10.dp)

@Composable
fun LynkDrawerContent(
    current: TabId,
    activeCount: Int,
    versionName: String,
    onSelect: (TabId) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier.width(280.dp),
        drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        drawerContainerColor = LynkColors.surface,
        drawerContentColor = LynkColors.text,
        drawerTonalElevation = 0.dp,
    ) {
        Spacer(Modifier.height(26.dp))
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(
                text = "LYNK",
                fontFamily = Mono,
                fontSize = 22.sp,
                letterSpacing = 5.sp,
                color = LynkColors.text,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "SSH-порты и терминал",
                fontSize = 11.sp,
                color = LynkColors.textMuted,
            )
        }

        Spacer(Modifier.height(20.dp))

        Column(Modifier.padding(horizontal = 10.dp)) {
            TabId.entries.forEachIndexed { index, tab ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                DrawerItem(
                    tab = tab,
                    selected = tab == current,
                    onClick = { onSelect(tab) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        DividerLine()

        Column(Modifier.padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (activeCount == 0) "нет соединений" else "$activeCount ${connectionsWord(activeCount)}",
                fontFamily = Mono,
                fontSize = 11.sp,
                color = LynkColors.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Lynk v$versionName",
                fontFamily = Mono,
                fontSize = 11.sp,
                color = LynkColors.textMuted,
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun DrawerItem(tab: TabId, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) LynkColors.text else LynkColors.textDim
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(ItemShape)
            .background(if (selected) LynkColors.surfaceHigh else LynkColors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(tab.iconRes()),
            contentDescription = tab.title(),
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = tab.title(),
            fontSize = 14.5.sp,
            color = color,
        )
    }
}

private fun connectionsWord(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "соединение"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "соединения"
    else -> "соединений"
}

private fun TabId.title(): String = when (this) {
    TabId.HOME -> "Дом"
    TabId.SERVERS -> "Узлы"
    TabId.MONITOR -> "Мониторинг"
    TabId.TUNNELS -> "Туннели"
    TabId.SETTINGS -> "Настройки"
}

private fun TabId.iconRes(): Int = when (this) {
    TabId.HOME -> R.drawable.ic_tab_home
    TabId.SERVERS -> R.drawable.ic_tab_servers
    TabId.MONITOR -> R.drawable.ic_tab_monitor
    TabId.TUNNELS -> R.drawable.ic_tab_tunnels
    TabId.SETTINGS -> R.drawable.ic_tab_settings
}
