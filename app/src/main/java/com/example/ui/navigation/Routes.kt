package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface WmsRoute {
    @Serializable data object Auth : WmsRoute
    @Serializable data object Dashboard : WmsRoute
    @Serializable data object ActiveAudit : WmsRoute
    @Serializable data object Receiving : WmsRoute
    @Serializable data object Returns : WmsRoute
    @Serializable data object ErrorReport : WmsRoute
    @Serializable data object InventoryCount : WmsRoute
    @Serializable data object PickingWave : WmsRoute
    @Serializable data object MasterDatabase : WmsRoute
    @Serializable data object Settings : WmsRoute
}
