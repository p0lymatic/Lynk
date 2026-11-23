package com.p0lymatic.lynk.ui

import androidx.compose.runtime.mutableStateListOf

enum class TabId { HOME, SERVERS, MONITOR, TUNNELS, SETTINGS }

sealed interface Route {
    data class Tab(val tab: TabId) : Route
    data class ServerDetail(val serverId: String) : Route
    data class ServerEdit(val serverId: String?) : Route
    data class ServiceEdit(val serviceId: String?, val serverId: String) : Route
    data class TunnelEdit(val tunnelId: String?, val serverId: String) : Route
    data class Terminal(val serverId: String) : Route
    data object MonitorSettings : Route
    data class Browser(
        val serverId: String,
        val serviceId: String?,
        val port: Int,
        val scheme: String = "http",
        val path: String = "/",
        val title: String = "",
        val host: String = "127.0.0.1",
    ) : Route
}

class Navigator {
    private val stack = mutableStateListOf<Route>(Route.Tab(TabId.HOME))

    val current: Route get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    val depth: Int get() = stack.size

    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun selectTab(tab: TabId) {
        popToRoot()
        stack[0] = Route.Tab(tab)
    }
}