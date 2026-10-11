package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.importing.api.ImportRepository
import ru.lavafrai.openid.models.account.ImportedAccount

internal suspend fun ImportRepository.decodeWithPasswordCopy(
    url: String,
    password: CharArray,
): ImportedAccount {
    val copy = password.copyOf()
    try {
        return decode(url, copy)
    } finally {
        copy.fill('\u0000')
    }
}
