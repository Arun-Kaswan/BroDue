package com.abk.brodue

data class Person(
    val id: String = "",
    val name: String = "",
    val mobile: String = "",
    val netAmount: Long = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val photoUrl: String = "",
    val hasUpdate: Boolean = false,
    val isSyncedState: Boolean = false,
    val leftGroup: Boolean = false
)