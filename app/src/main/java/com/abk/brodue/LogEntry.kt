package com.abk.brodue

data class LogEntry(
    val personId: String,
    val personName: String,
    val tx: Transaction
)
