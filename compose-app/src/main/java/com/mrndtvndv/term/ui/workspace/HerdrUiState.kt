package com.mrndtvndv.term.ui.workspace

import com.mrndtvndv.term.server.HerdrWorkspaceResolver

data class HerdrUiState(
    val workspaces: List<HerdrWorkspaceResolver.HerdrWorkspaceNode> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
