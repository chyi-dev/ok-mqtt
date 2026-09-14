package com.ok.mqtt.room

import androidx.room.*
import com.ok.mqtt.room.entity.MqMessageEntity

@Dao
interface MqMessageDao {

    @get:Query("SELECT * FROM MQMessageEntity")
    val all: List<MqMessageEntity>

    @Query("SELECT * FROM MQMessageEntity WHERE clientHandle = :clientHandle ORDER BY timestamp ASC")
    suspend fun allArrived(clientHandle: String): List<MqMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mqMessageEntity: MqMessageEntity): Long

    @Update
    fun updateAll(vararg mqMessageEntity: MqMessageEntity)

    @Delete
    fun delete(mqMessageEntity: MqMessageEntity)

    @Query("DELETE FROM MQMessageEntity WHERE clientHandle = :clientHandle AND messageId = :id")
    suspend fun deleteId(clientHandle: String, id: String): Int

    @Query("DELETE FROM MQMessageEntity WHERE clientHandle = :clientHandle")
    suspend fun deleteClientHandle(clientHandle: String): Int

}

