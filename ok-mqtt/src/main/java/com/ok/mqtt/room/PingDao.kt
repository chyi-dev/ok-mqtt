package com.ok.mqtt.room

import androidx.lifecycle.LiveData
import androidx.room.*
import com.ok.mqtt.room.entity.PingEntity

@Dao
interface PingDao {

    @get:Query("SELECT * FROM PingEntity ORDER BY timestamp ASC")
    val all: LiveData<List<PingEntity>>

    @Query("SELECT * FROM PingEntity WHERE success = :statePing ORDER BY timestamp ASC")
    fun allByState(statePing: Boolean): LiveData<List<PingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pingEntity: PingEntity): Long

    @Update
    fun updateAll(vararg pingEntities: PingEntity)

    @Delete
    fun delete(pingEntity: PingEntity)

    @Query("DELETE FROM PingEntity WHERE success = :statePing")
    fun deleteState(statePing: Boolean): Int

    @Query("DELETE FROM PingEntity WHERE timeStamp IN (SELECT timeStamp FROM PingEntity ORDER BY timeStamp DESC LIMIT 1 OFFSET :keepCount)")
    suspend fun removeOldData(keepCount: Int)

}

