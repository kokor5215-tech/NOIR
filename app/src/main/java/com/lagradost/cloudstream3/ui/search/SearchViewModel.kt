package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.utils.NoirProviderHealth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.SearchRanker
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class ExpandableSearchList(
    var list: List<SearchResponse>, var currentPage: Int, var hasNext: Boolean,
)

const val SEARCH_HISTORY_KEY = "search_history"

class SearchViewModel : ViewModel() {
    private val _searchResponse: MutableLiveData<Resource<ExpandableSearchList>> =
        MutableLiveData()
    val searchResponse: LiveData<Resource<ExpandableSearchList>> get() = _searchResponse

    private val _currentSearch: MutableLiveData<Map<String, ExpandableSearchList>> =
        MutableLiveData()
    val currentSearch: LiveData<Map<String, ExpandableSearchList>> get() = _currentSearch

    private val _currentHistory: MutableLiveData<List<SearchHistoryItem>> = MutableLiveData()
    val currentHistory: LiveData<List<SearchHistoryItem>> get() = _currentHistory

    private val _searchSuggestions: MutableLiveData<List<String>> = MutableLiveData()
    val searchSuggestions: LiveData<List<String>> get() = _searchSuggestions

    // NOIR fix: judul trending untuk empty state (layar hitam polos saat
    // riwayat kosong). Dimuat sekali per sesi, di-cache jaringan oleh API.
    private val _trending: MutableLiveData<List<String>> = MutableLiveData()
    val trending: LiveData<List<String>> get() = _trending
    private var trendingRequested = false

    fun fetchTrending() {
        if (trendingRequested) return
        trendingRequested = true
        ioSafe {
            _trending.postValue(SearchSuggestionApi.getTrending())
        }
    }

    private var suggestionJob: Job? = null

    private var repos = apis.withLock { apis.map { APIRepository(it) } }

    fun clearSearch() {
        _searchResponse.postValue(Resource.Success(ExpandableSearchList(emptyList(), 0, false)))
        _currentSearch.postValue(emptyMap())
        expandableSearches.clear()
    }

    var lastQuery: String? = null

    /** Save which providers can searched again and which search result page they are on.
     * Maps provider name to search list.
     * @see [HomeViewModel.expandable] */
    private val expandableSearches: MutableMap<String, ExpandableSearchList> = mutableMapOf()

    private var currentSearchIndex = 0
    private var onGoingSearch: Job? = null

    // NOIR perf: cache hasil pencarian per kombinasi query+provider (TTL 5
    // menit, maks 25 entri). Ketik ulang judul yang sama = hasil INSTAN tanpa
    // menembak jaringan — menghilangkan lag repeat-search.
    private data class CachedSearch(
        val at: Long,
        val map: Map<String, ExpandableSearchList>,
    )

    private val searchCache = LinkedHashMap<String, CachedSearch>()

    private fun cacheKey(query: String, providers: Set<String>) =
        query.lowercase().trim() + "|" + providers.sorted().joinToString(",")

    fun reloadRepos() {
        repos = apis.withLock { apis.map { APIRepository(it) } }
    }

    fun searchAndCancel(
        query: String,
        providersActive: Set<String> = setOf(),
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) {
        currentSearchIndex++
        onGoingSearch?.cancel()
        onGoingSearch = search(query, providersActive, ignoreSettings, isQuickSearch)
    }

    fun updateHistory() = ioSafe {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: emptyList()
        _currentHistory.postValue(items)
    }

    /**
     * Fetches search suggestions with debouncing.
     * Waits 300ms before making the API call to avoid too many requests.
     * 
     * @param query The search query to get suggestions for
     */
    fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()
        
        if (query.isBlank() || query.length < 2) {
            _searchSuggestions.postValue(emptyList())
            return
        }
        
        suggestionJob = ioSafe {
            // NOIR perf: debounce 300ms sudah dilakukan SearchFragment sebelum
            // memanggil ini — delay kedua di sini hanya menambah latensi saran.
            val suggestions = SearchSuggestionApi.getSuggestions(query)
            _searchSuggestions.postValue(suggestions)
        }
    }

    /**
     * Clears the current search suggestions.
     */
    fun clearSuggestions() {
        suggestionJob?.cancel()
        _searchSuggestions.postValue(emptyList())
    }

    private val lock: MutableSet<String> = mutableSetOf()

    // NOIR perf/stabilitas: expandableSearches ditulis PARALEL oleh amap() dan
    // dikirim ke observer di main thread. Tanpa sinkronisasi bisa terjadi
    // ConcurrentModificationException atau snapshot setengah jadi.
    // postCurrentSearchLocked() mengirim SALINAN (list di-copy) supaya mutasi
    // provider berikutnya tidak mengacak-acak data yang sedang dirender.
    private val searchMapLock = Any()

    private fun postCurrentSearchLocked() {
        _currentSearch.postValue(
            expandableSearches.mapValues { (_, v) -> v.copy(list = v.list.toList()) }
        )
    }

    // ExpandableHomepageList because the home adapter is reused in the search fragment
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? {
        if (lock.contains(name)) return null
        val query = lastQuery ?: return null
        val repo = repos.find { it.name == name } ?: return null

        lock += name

        expandableSearches[name]?.let { current ->
            debugAssert({ !current.hasNext }) {
                "Expand called when not needed"
            }

            val nextPage = current.currentPage + 1
            val next = repo.search(query, nextPage)
            if (next is Resource.Success) {
                val nextValue = next.value
                expandableSearches[name]?.apply {
                    this.hasNext = nextValue.hasNext
                    this.currentPage = nextPage

                    debugWarning({ nextValue.items.any { outer -> this.list.any { it.url == outer.url } } }) {
                        "Expanded search contained an item that was previously already in the list.\nQuery = $query, ${nextValue.items} = ${this.list}"
                    }

                    // just to be sure we are not adding the same shit for some reason
                    // Avoids weird behavior in the recyclerview by recreating the list
                    this.list = (this.list + nextValue.items).distinctBy { it.url }
                } ?: debugWarning {
                    "Expanded an item not in search load named $name, current list is ${expandableSearches.keys}"
                }
            } else {
                current.hasNext = false
            }

            synchronized(searchMapLock) {
                _searchResponse.postValue(Resource.Success(bundleSearch(expandableSearches, query)))
                postCurrentSearchLocked()
            }
        }

        lock -= name

        val item = expandableSearches[name] ?: return null
        return HomeViewModel.ExpandableHomepageList(
            HomePageList(name, item.list),
            item.currentPage,
            item.hasNext
        )
    }

    private fun bundleSearch(
        lists: MutableMap<String, ExpandableSearchList>,
        query: String,
    ): ExpandableSearchList {
        if (lists.size == 1) {
            val single = lists.values.first()
            // NOIR: tetap diurutkan walau hanya satu penyedia, supaya judul
            // yang paling mirip kueri muncul paling atas.
            return ExpandableSearchList(
                SearchRanker.rank(query, single.list),
                single.currentPage,
                single.hasNext
            )
        }

        val list = ArrayList<SearchResponse>()
        val nestedList =
            lists.map { it.value.list }

        // I do it this way to move the relevant search results to the top
        var index = 0
        while (true) {
            var added = 0
            for (sublist in nestedList) {
                if (sublist.size > index) {
                    list.add(sublist[index])
                    added++
                }
            }
            if (added == 0) break
            index++
        }

        // NOIR: round-robin di atas tetap jadi urutan dasar; ranking fuzzy
        // bersifat STABIL sehingga hanya mengangkat yang paling cocok.
        return ExpandableSearchList(SearchRanker.rank(query, list), 1, false)
    }

    private fun search(
        query: String,
        providersActive: Set<String>,
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) =
        viewModelScope.launchSafe {
            val currentIndex = currentSearchIndex
            if (query.length <= 1) {
                clearSearch()
                return@launchSafe
            }

            if (!isQuickSearch) {
                val key = query.hashCode().toString()
                setKey(
                    "$currentAccount/$SEARCH_HISTORY_KEY",
                    key,
                    SearchHistoryItem(
                        searchedAt = System.currentTimeMillis(),
                        searchText = query,
                        type = emptyList(), // TODO implement tv type
                        key = key,
                    )
                )
            }

            // NOIR perf + keandalan: cache hit => hasil INSTAN dari memori,
            // lalu refresh jaringan tetap jalan DIAM-DIAM di latar belakang
            // (tanpa Loading, tanpa mengosongkan layar) supaya pencarian ulang
            // dengan kata kunci yang sama tidak pernah menampilkan hasil kosong
            // atau basi. Tanpa cache, jalur normal: Loading lalu jaringan.
            val cacheKeyNow = cacheKey(query, providersActive)
            val hit = synchronized(searchCache) { searchCache[cacheKeyNow] }
            val cacheValid = !isQuickSearch && hit != null &&
                System.currentTimeMillis() - hit.at < 5 * 60_000L

            if (cacheValid && hit != null) {
                lastQuery = query
                synchronized(searchMapLock) {
                    expandableSearches.clear()
                    hit.map.forEach { (k, v) ->
                        expandableSearches[k] = v.copy(list = v.list.toList())
                    }
                    postCurrentSearchLocked()
                    _searchResponse.postValue(
                        Resource.Success(bundleSearch(expandableSearches, query))
                    )
                }
            } else {
                _searchResponse.postValue(Resource.Loading())
                _currentSearch.postValue(emptyMap())
                expandableSearches.clear()
            }

            lastQuery = query

            withContext(Dispatchers.IO) { // This interrupts UI otherwise
                repos.filter { a ->
                    (ignoreSettings || (providersActive.isEmpty() || providersActive.contains(a.name))) && (!isQuickSearch || a.hasQuickSearch)
                }.filter { a ->
                    // NOIR: provider cooldown tidak ditanyai = tidak buang waktu.
                    !NoirProviderHealth.isBlocked(a.name)
                }.amap { a -> // Parallel
                    val search = if (isQuickSearch) a.quickSearch(query) else a.search(query, 1)
                    if (currentSearchIndex != currentIndex) return@amap
                    // NOIR: catat kesehatan provider.
                    if (search is Resource.Success) NoirProviderHealth.recordOk(a.name)
                    else NoirProviderHealth.recordFail(
                        a.name,
                        // NOIR FIX: kegagalan DNS/socket tidak menghukum provider.
                        (search as? Resource.Failure)?.isNetworkError == true
                    )
                    synchronized(searchMapLock) {
                        if (search is Resource.Success) {
                            val searchValue = search.value
                            expandableSearches[a.name] =
                                ExpandableSearchList(searchValue.items, 1, searchValue.hasNext)
                        }
                        postCurrentSearchLocked()
                    }
                }

                if (currentSearchIndex != currentIndex) return@withContext // this should prevent rewrite of existing data bug

                val list = synchronized(searchMapLock) {
                    postCurrentSearchLocked()
                    bundleSearch(expandableSearches, query)
                }

                _searchResponse.postValue(Resource.Success(list))

                // NOIR perf: simpan snapshot hasil buat cache repeat-search.
                if (!isQuickSearch) {
                    val snapshot = synchronized(searchMapLock) {
                        expandableSearches.mapValues { (_, v) ->
                            v.copy(list = v.list.toList())
                        }
                    }
                    synchronized(searchCache) {
                        searchCache[cacheKeyNow] =
                            CachedSearch(System.currentTimeMillis(), snapshot)
                        while (searchCache.size > 25) {
                            searchCache.remove(searchCache.keys.first())
                        }
                    }
                }
            }
        }
}
