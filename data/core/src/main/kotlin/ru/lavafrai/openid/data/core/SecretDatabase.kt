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
    /**
     * Version of the authenticated-data format the row was encrypted with (see
     * [VaultCipher.associatedData]). Rows written by schema 1 keep [CRYPTO_V1] after
     * [SecretDatabase.MIGRATION_1_2]: their ciphertext and wrapped key stay bound to the v1 AAD,
     * so they must be opened with it and are re-encrypted to [CRYPTO_CURRENT] on next unlock.
     */
    @ColumnInfo(defaultValue = "1") val cryptoVersion: Int = CRYPTO_CURRENT,
) {
    companion object {
        const val CRYPTO_V1 = 1
        const val CRYPTO_V2 = 2
        const val CRYPTO_CURRENT = CRYPTO_V2
    }
}

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
        /**
         * Adds [SecretRecord.tokenProfile] (existing rows are non-token profiles: schema 1 required a
         * PIN salt) and [SecretRecord.cryptoVersion] = 1, so existing ciphertext keeps opening with
         * the v1 authenticated data it was sealed with.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE secrets ADD COLUMN tokenProfile INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE secrets ADD COLUMN cryptoVersion INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
