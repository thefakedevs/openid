package ru.lavafrai.openid.data.repositories.importing.api

import ru.lavafrai.openid.models.account.ImportedAccount

interface ImportRepository {
    /** Decrypts and validates an import without network calls or persistence; password is never retained. */
    suspend fun decode(
        url: String,
        password: CharArray,
    ): ImportedAccount
}
