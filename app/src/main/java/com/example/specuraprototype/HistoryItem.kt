package com.example.specuraprototype

data class HistoryItem(
    val id: Long = System.currentTimeMillis(),
    val imageUri: String,
    val material: String,
    val damage: String,
    val confidence: Float,
    val prompt: String,
    val damageSignal: Float,
    val severityScore: Float,
    val severityLabel: String,
    val timestamp: Long = System.currentTimeMillis()
)
