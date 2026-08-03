package com.example.prolestimesheet.model

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PermissionInfo(
    val key: String,
    val displayName: String
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RolePermission(
    val permission: String,
    val canView: Boolean = true,
    val canCreate: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Role(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val permissions: List<RolePermission> = emptyList()
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class UserPermissionOverride(
    val permission: String,
    val canView: Boolean? = null,
    val canCreate: Boolean? = null,
    val canEdit: Boolean? = null,
    val canDelete: Boolean? = null,
    val isOverride: Boolean = false  // 🆕
)

/**
 * Effective-права пользователя (роли + override'ы), полученные с сервера.
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PermissionActions(
    val canView: Boolean = false,
    val canCreate: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false
)

/**
 * Полная карта прав пользователя.
 * Ключ — permission key (например, "projects"), значение — PermissionActions.
 */
typealias UserEffectivePermissions = Map<String, PermissionActions>