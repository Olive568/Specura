package com.example.specuraprototype

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ScanDao {
    @Insert
    fun insert(scan: ScanEntity)

    @Delete
    fun deleteScan(scan: ScanEntity)

    @Query("SELECT * FROM scans WHERE location = :location ORDER BY timestamp DESC LIMIT 3")
    fun getLastThreeScansByLocation(location: String): List<ScanEntity>

    @Query("SELECT * FROM scans ORDER BY timestamp DESC")
    fun getAllScans(): List<ScanEntity>

    @Query("SELECT DISTINCT location FROM scans ORDER BY location ASC")
    fun getUniqueLocations(): List<String>
}
