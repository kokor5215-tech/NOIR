package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.syncproviders.providers.Addic7ed
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.KitsuApi
import com.lagradost.cloudstream3.syncproviders.providers.LocalList
import com.lagradost.cloudstream3.syncproviders.providers.MALApi
import com.lagradost.cloudstream3.syncproviders.providers.NoirISubtitles
import com.lagradost.cloudstream3.syncproviders.providers.NoirSubtitlecat
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SubDlApi
import com.lagradost.cloudstream3.syncproviders.providers.SubSourceApi
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.videoskip.AnimeSkipAuth
import java.util.concurrent.TimeUnit

abstract class AccountManager {
    companion object {
        // --- MODIFIKASI NOIR (DIPERTAHANKAN): Branding APP_STRING ---
        const val APP_STRING = "noir"
        const val APP_STRING_REPO = "noirrepo"
        const val APP_STRING_PLAYER = "noirplayer"

        // Instantly start the search given a query
        const val APP_STRING_SEARCH = "noirsearch"

        // Instantly resume watching a show
        const val APP_STRING_RESUME_WATCHING = "noircontinuewatching"

        const val APP_STRING_SHARE = "csshare"

        const val ACCOUNT_TOKEN = "auth_tokens"
        const val ACCOUNT_IDS = "auth_ids"
        const val NONE_ID: Int = -1
        // ---------------------------------------------------------------

        val malApi = MALApi()
        val kitsuApi = KitsuApi()
        val aniListApi = AniListApi()
        val simklApi = SimklApi()
        val localListApi = LocalList()

        val openSubtitlesApi = OpenSubtitlesApi()
        val addic7ed = Addic7ed()
        val subDlApi = SubDlApi()
        val subSourceApi = SubSourceApi()
        // NOIR: sumber ekstra tanpa login, fokus subtitle Indonesia.
        val noirSubtitlecat = NoirSubtitlecat()
        val noirISubtitles = NoirISubtitles()
        val animeSkipApi = AnimeSkipAuth()

        // --- MODIFIKASI NOIR: cachedAccounts inisialisasi (anti NPE) ---
        var cachedAccounts: MutableMap<String, Array<AuthData>> = mutableMapOf()
        var cachedAccountIds: MutableMap<String, Int> = mutableMapOf()

        fun accounts(prefix: String): Array<AuthData> {
            require(prefix != "NONE")
            return getKey<Array<AuthData>>(
                ACCOUNT_TOKEN,
                "${prefix}/${DataStoreHelper.currentAccount}"
            ) ?: arrayOf()
        }

        fun updateAccounts(prefix: String, array: Array<AuthData>) {
            require(prefix != "NONE")
            setKey(ACCOUNT_TOKEN, "${prefix}/${DataStoreHelper.currentAccount}", array)
            synchronized(cachedAccounts) {
                cachedAccounts[prefix] = array
            }
        }

        fun updateAccountsId(prefix: String, id: Int) {
            require(prefix != "NONE")
            setKey(ACCOUNT_IDS, "${prefix}/${DataStoreHelper.currentAccount}", id)
            synchronized(cachedAccountIds) {
                cachedAccountIds[prefix] = id
            }
        }

        // --- MODIFIKASI NOIR: animeSkipApi & subSourceApi tidak digunakan ---
        // Patch: keduanya sekarang di-include supaya build match dengan SettingsAccount.kt Cloudstream
        val allApis = arrayOf(
            SyncRepo(malApi),
            SyncRepo(kitsuApi),
            SyncRepo(aniListApi),
            SyncRepo(simklApi),
            SyncRepo(localListApi),
            SubtitleRepo(openSubtitlesApi),
            SubtitleRepo(addic7ed),
            SubtitleRepo(subDlApi),
            SubtitleRepo(subSourceApi),
            PlainAuthRepo(animeSkipApi)
        )

        fun updateAccountIds() {
            val ids = mutableMapOf<String, Int>()
            for (api in allApis) {
                ids.put(
                    api.idPrefix,
                    getKey<Int>(
                        ACCOUNT_IDS,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        NONE_ID
                    ) ?: NONE_ID
                )
            }
            synchronized(cachedAccountIds) {
                cachedAccountIds = ids
            }
        }

        init {
            val data = mutableMapOf<String, Array<AuthData>>()
            val ids = mutableMapOf<String, Int>()
            for (api in allApis) {
                data.put(api.idPrefix, accounts(api.idPrefix))
                ids.put(
                    api.idPrefix,
                    getKey<Int>(
                        ACCOUNT_IDS,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        NONE_ID
                    ) ?: NONE_ID
                )
            }
            cachedAccounts = data
            cachedAccountIds = ids
        }

        // I do not want to place this in the init block as JVM initialization order is weird, and it may cause exceptions
        // accessing other classes
        fun initMainAPI() {
            LoadResponse.malIdPrefix = malApi.idPrefix
            LoadResponse.kitsuIdPrefix = kitsuApi.idPrefix
            LoadResponse.aniListIdPrefix = aniListApi.idPrefix
            LoadResponse.simklIdPrefix = simklApi.idPrefix
        }

        val subtitleProviders = arrayOf(
            SubtitleRepo(noirSubtitlecat),
            SubtitleRepo(noirISubtitles),
            SubtitleRepo(subSourceApi),
            SubtitleRepo(openSubtitlesApi),
            SubtitleRepo(subDlApi),
            SubtitleRepo(addic7ed)
        )

        val syncApis = arrayOf(
            SyncRepo(malApi),
            SyncRepo(kitsuApi),
            SyncRepo(aniListApi),
            SyncRepo(simklApi),
            SyncRepo(localListApi)
        )

        // --- MODIFIKASI NOIR: secondsToReadable lebih clean (no dead code) ---
        fun secondsToReadable(seconds: Int, completedValue: String): String {
            var secondsLong = seconds.toLong()
            val days = TimeUnit.SECONDS.toDays(secondsLong)
            secondsLong -= TimeUnit.DAYS.toSeconds(days)

            val hours = TimeUnit.SECONDS.toHours(secondsLong)
            secondsLong -= TimeUnit.HOURS.toSeconds(hours)

            val minutes = TimeUnit.SECONDS.toMinutes(secondsLong)
            if (minutes < 0) {
                return completedValue
            }
            return "${if (days != 0L) "$days" + "d " else ""}${if (hours != 0L) "$hours" + "h " else ""}${minutes}m"
        }
    }
}
