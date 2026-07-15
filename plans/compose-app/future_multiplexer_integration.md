# Future-Proofing: Remote Multiplexer Integration

This document outlines the feasibility, architecture, and design considerations for supporting a **remote terminal multiplexer** (such as standard tmux/screen, Mosh-like state sync protocols, or a custom native multiplexing system) in our new Jetpack Compose-native application (`com.mrndtvndv.term`).

---

## 1. Feasibility Analysis

Integrating a remote multiplexer is **highly feasible** because we have abstracted the presentation layer (Compose UI + [TerminalView](file:///Volumes/realme/Dev/termux-ghostty/terminal-view/src/main/java/com/termux/view/TerminalView.java)) from the network transport layer via domain interfaces.

Depending on the *type* of multiplexer you want to build or connect to, the pipeline adapts as follows:

### Scenario A: Standard Terminal-Based Multiplexer (e.g., tmux / screen)
* **How it works:** The multiplexer runs on the remote server and outputs standard ANSI/VT escape sequences over a standard SSH channel.
* **Feasibility:** **100% out-of-the-box compatibility.**
  Because our `SshSession` implements standard shell terminal capabilities, starting `tmux` on the remote host renders and functions immediately inside [TerminalView](file:///Volumes/realme/Dev/termux-ghostty/terminal-view/src/main/java/com/termux/view/TerminalView.java).

### Scenario B: Custom Multiplexer Protocol (Mosh / Eternal Terminal Style)
* **How it works:** The client does not parse raw VT sequences. Instead, the server multiplexer runs the shell, parses the VT sequences locally, and transmits **diffs of the terminal grid (cells, colors, cursor positions)** to the client using a secure UDP/TCP state synchronization protocol (SSP).
* **Feasibility:** **Highly Feasible with our Interface Architecture.**
  Instead of sending raw stream bytes to the Ghostty parser, your custom multiplexer client receives cell updates, decodes them, and directly manipulates the cell buffer cache.

---

## 2. Dynamic Server-Driven Workspace Layout (Tabs & Splits)

If you build your own remote server-side multiplexer daemon (e.g., in Zig or Go), you can sync the layout state dynamically so that **tabs and split-panes are rendered as native Compose widgets** rather than standard terminal character borders.

### A. The Layout Tree Protocol
The server holds the workspace session state and transmits a serialized layout tree to the client. On Android, we parse this payload into a recursive layout tree:

```kotlin
sealed interface LayoutNode {
    // A Leaf node holding a single Shell session
    data class PaneNode(val paneId: String, val title: String) : LayoutNode
    
    // A Node representing a split layout division
    data class SplitNode(
        val isVertical: Boolean, 
        val first: LayoutNode, 
        val second: LayoutNode,
        val splitRatio: Float = 0.5f
    ) : LayoutNode
}

data class TabItem(
    val tabId: String,
    val title: String,
    val rootLayoutNode: LayoutNode
)
```

---

### B. Recursive Compose Renderer
In Compose, we can dynamically build split-panes recursively using `Weight` layouts. Since each `PaneNode` maps to a distinct shell connection stream, we instantiate a separate [TerminalView](file:///Volumes/realme/Dev/termux-ghostty/terminal-view/src/main/java/com/termux/view/TerminalView.java) inside its own focus-managed container:

```kotlin
@Composable
fun LayoutNodeRenderer(
    node: LayoutNode,
    shellSessions: Map<String, TerminalSession>,
    modifier: Modifier = Modifier
) {
    when (node) {
        is LayoutNode.PaneNode -> {
            val session = shellSessions[node.paneId]
            if (session != null) {
                // Render separate focus-managed terminal instance per pane
                TerminalWorkspaceContainer(
                    session = session,
                    modifier = modifier.fillMaxSize()
                )
            } else {
                LoadingPanePlaceholder(node.title)
            }
        }
        is LayoutNode.SplitNode -> {
            if (node.isVertical) {
                Row(modifier = modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(node.splitRatio)) {
                        LayoutNodeRenderer(node.first, shellSessions)
                    }
                    VerticalDivider(thickness = 1.dp)
                    Box(modifier = Modifier.weight(1f - node.splitRatio)) {
                        LayoutNodeRenderer(node.second, shellSessions)
                    }
                }
            } else {
                Column(modifier = modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(node.splitRatio)) {
                        LayoutNodeRenderer(node.first, shellSessions)
                    }
                    HorizontalDivider(thickness = 1.dp)
                    Box(modifier = Modifier.weight(1f - node.splitRatio)) {
                        LayoutNodeRenderer(node.second, shellSessions)
                    }
                }
            }
        }
    }
}
```

---

### C. Native Compose Tabs Integration
The top-level workspace renders the server-driven tabs using Compose's standard navigation and layout row:

```kotlin
@Composable
fun MultiplexerWorkspace(
    tabs: List<TabItem>,
    shellSessions: Map<String, TerminalSession>
) {
    var activeTabIndex by remember { mutableStateOf(0) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = activeTabIndex) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = activeTabIndex == index,
                    onClick = { activeTabIndex = index },
                    text = { Text(tab.title) }
                )
            }
        }
        
        val activeTab = tabs.getOrNull(activeTabIndex)
        if (activeTab != null) {
            LayoutNodeRenderer(
                node = activeTab.rootLayoutNode,
                shellSessions = shellSessions,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
```

---

## 3. Advantages of Server-Driven Native layouts

1. **Independent Terminal Instances:**
   Because each split pane hosts a separate [TerminalView](file:///Volumes/realme/Dev/termux-ghostty/terminal-view/src/main/java/com/termux/view/TerminalView.java) instance, users can independently change font sizes, copy texts, scroll backward, or trigger gestures in one split-pane without affecting the layout of other panes.
2. **Dynamic Drag-and-Drop:**
   Tabs and split-panes are standard Compose views. You can implement drag-to-split or drag-to-reorder tabs natively on Android, and serialize the resulting structure back to the server to update other connected clients.
3. **Session Persistence:**
   If the mobile device experiences network roaming, the server multiplexer retains the shell states. Once reconnected, the client pulls the layout tree and shell channels to restore the exact tab/split configuration instantly.
