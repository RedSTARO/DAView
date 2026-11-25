@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.daview.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daview.app.data.AppState
import com.daview.app.data.Screen
import com.daview.app.ui.AppMark
import com.daview.app.ui.AppMenu
import com.daview.app.ui.TipIconButton
import com.daview.app.ui.Tooltip
import com.daview.shared.model.LibraryKind

private enum class Destination(val label: String, val filled: ImageVector, val outlined: ImageVector) {
    Home("首页", Icons.Filled.Home, Icons.Outlined.Home),
    Libraries("媒体库", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    Search("搜索", Icons.Filled.Search, Icons.Outlined.Search),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
}

internal fun libraryIcon(kind: LibraryKind) = when (kind) {
    LibraryKind.MOVIE -> Icons.Filled.Movie
    LibraryKind.SERIES -> Icons.Filled.Tv
    LibraryKind.ANIME -> Icons.Filled.Animation
    LibraryKind.OTHER -> Icons.Filled.FolderOpen
}

@Composable
internal fun AppNavigation(
    state: AppState,
    rail: Boolean,
    expanded: Boolean,
    toggleRail: (() -> Unit)?,
    select: (Screen) -> Unit
) {
    val selected = when (state.browseDestination) {
        is Screen.Library -> Destination.Libraries
        Screen.Search -> Destination.Search
        Screen.Settings -> Destination.Settings
        else -> Destination.Home
    }
    var libraryMenuOpen by remember(rail, expanded) { mutableStateOf(false) }
    val scanning = state.scanStatus.any { it.running }
    val choose: (Destination) -> Unit = { destination ->
        when (destination) {
            Destination.Home -> select(Screen.Home)
            Destination.Search -> select(Screen.Search)
            Destination.Settings -> select(Screen.Settings)
            Destination.Libraries -> {
                val only = state.libraries.singleOrNull()
                if (only == null) libraryMenuOpen = true else select(Screen.Library(only.id))
            }
        }
    }
    val icon: @Composable (Destination) -> Unit = { destination ->
        val vector = if (destination == selected) destination.filled else destination.outlined
        when {
            destination == Destination.Libraries -> Box {
                Icon(vector, contentDescription = null)
                LibraryMenu(state, libraryMenuOpen, dismiss = { libraryMenuOpen = false }, select = select)
            }
            destination == Destination.Settings && scanning -> BadgedBox(badge = { Badge() }) {
                Icon(vector, contentDescription = null)
            }
            else -> Icon(vector, contentDescription = null)
        }
    }
    val itemModifier: (Destination) -> Modifier = { destination ->
        if (destination == Destination.Settings && scanning) {
            Modifier.semantics { stateDescription = "正在扫描媒体库" }
        } else Modifier
    }
    if (rail) {
        // The adaptive shell measures this rail outside the live content panes so
        // expanding it never recreates a page or puts any controls on a hinge.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showBrand = maxHeight >= 360.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showBrand || toggleRail != null) {
                    RailHeader(expanded, showBrand, toggleRail)
                }
                Destination.entries.forEach { destination ->
                    Tooltip(destination.label) {
                        WideNavigationRailItem(
                            selected = destination == selected,
                            onClick = { choose(destination) },
                            icon = { icon(destination) },
                            label = { DestinationLabel(destination, selected, centered = !expanded) },
                            railExpanded = expanded,
                            iconPosition = if (expanded) NavigationItemIconPosition.Start else NavigationItemIconPosition.Top,
                            modifier = itemModifier(destination).fillMaxWidth()
                        )
                    }
                }
            }
        }
    } else {
        // The alpha ShortNavigationBar lays out its children intrinsically. At
        // exact-height constraints inside an Android inset pane it may expose
        // just the first entry. The shell already provides its Material surface
        // and height; equal-weight children keep labels and hit areas in bounds.
        Row(Modifier.fillMaxSize().selectableGroup()) {
            Destination.entries.forEach { destination ->
                ShortNavigationBarItem(
                    selected = destination == selected,
                    onClick = { choose(destination) },
                    icon = { icon(destination) },
                    label = { DestinationLabel(destination, selected, centered = true) },
                    modifier = itemModifier(destination).weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun RailHeader(expanded: Boolean, showBrand: Boolean, toggle: (() -> Unit)?) {
    val toggleButton: @Composable () -> Unit = {
        if (toggle != null) {
            val label = if (expanded) "收起导航" else "展开导航"
            TipIconButton(label, onClick = toggle) {
                Icon(if (expanded) Icons.Filled.MenuOpen else Icons.Filled.Menu, contentDescription = label)
            }
        }
    }
    if (expanded && showBrand) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppMark()
            Text("DAView", Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
            toggleButton()
        }
    } else if (toggle != null) {
        toggleButton()
    } else {
        Box(Modifier.padding(bottom = 12.dp)) { AppMark() }
    }
}

@Composable
private fun DestinationLabel(destination: Destination, selected: Destination, centered: Boolean) {
    Text(
        destination.label,
        style = if (selected == destination) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        maxLines = 2
    )
}

@Composable
private fun LibraryMenu(state: AppState, open: Boolean, dismiss: () -> Unit, select: (Screen) -> Unit) {
    AppMenu(expanded = open, onDismissRequest = dismiss) {
        if (state.libraries.isEmpty()) {
            DropdownMenuItem(text = { Text("还没有媒体库") }, onClick = {}, enabled = false)
            DropdownMenuItem(text = { Text("去设置里添加…") }, onClick = {
                dismiss()
                select(Screen.Settings)
            })
        } else {
            state.libraries.forEach { library ->
                DropdownMenuItem(
                    leadingIcon = { Icon(libraryIcon(library.kind), contentDescription = null) },
                    text = { Text(library.name) },
                    onClick = {
                        dismiss()
                        select(Screen.Library(library.id))
                    }
                )
            }
        }
    }
}
