package com.abk.brodue

data class Transaction(
    val id: String = "",
    val category: String = "",
    val amount: Long = 0,
    val type: String = "gave",
    val note: String = "",
    val createdAt: Long = 0L,
    val savedAt: Long = 0L,
    val unseen: Boolean = false
) : java.io.Serializable