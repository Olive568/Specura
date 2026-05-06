package com.example.specuraprototype

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ScanDao {
    @Insert
    fun insert(scan: ScanEntity)

    @Query("SELECT * FROM scans WHERE location = :location ORDER BY timestamp DESC LIMIT 3")
    fun getLastThreeScansByLocation(location: String): List<ScanEntity>

    @Query("SELECT * FROM scans ORDER BY timestamp DESC")
    fun getAllScans(): List<ScanEntity>
}
