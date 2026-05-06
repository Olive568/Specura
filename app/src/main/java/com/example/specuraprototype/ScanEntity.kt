package com.example.specuraprototype

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val location: String,
    val jsonData: String,
    val timestamp: Long
)
