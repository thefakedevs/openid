package ru.lavafrai.openid.data.core

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "secrets")
data class SecretRecord(
    @PrimaryKey val slot: String,
    val accountId: Long,
    val protection: String,
    val alias: String,
    val wrappedKey: ByteArray,
    val wrappingIv: ByteArray,
    val ciphertext: ByteArray,
    val iv: ByteArray,
)

@Dao
interface SecretDao {
    @Query("SELECT * FROM secrets WHERE slot = :slot")
    suspend fun read(slot: String): SecretRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(record: SecretRecord)

    @Query("DELETE FROM secrets WHERE slot = :slot")
    suspend fun delete(slot: String)

    @Query("SELECT alias FROM secrets")
    suspend fun aliases(): List<String>
}

@Database(entities = [SecretRecord::class], version = 1, exportSchema = true)
abstract class SecretDatabase : RoomDatabase() {
    abstract fun secrets(): SecretDao
}
