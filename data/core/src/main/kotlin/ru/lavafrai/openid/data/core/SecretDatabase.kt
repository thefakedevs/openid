package ru.lavafrai.openid.data.core

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    /**
     * Profile kind of the stored account, written atomically with the account row in the same
     * [SecretDao.write]. For the "account" slot it mirrors [ru.lavafrai.openid.models.account
     * .ImportedDigitalId.tokenProfile]; always false for other slots. Being part of the account
     * record makes the profile type survive any crash on the exact boundary of the account write,
     * and [VaultCipher.associatedData] binds it as authenticated data so the column cannot be
     * flipped without failing decryption.
     */
    @ColumnInfo(defaultValue = "0") val tokenProfile: Boolean = false,
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

@Database(entities = [SecretRecord::class], version = 2, exportSchema = true)
abstract class SecretDatabase : RoomDatabase() {
    abstract fun secrets(): SecretDao

    companion object {
        /** Adds the [SecretRecord.tokenProfile] column, defaulting existing rows to a non-token profile. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE secrets ADD COLUMN tokenProfile INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
