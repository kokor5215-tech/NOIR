package com.lagradost.cloudstream3.ui.search

import android.app.Activity
import android.content.Intent
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentSearchBinding
import com.lagradost.cloudstream3.databinding.HomeSelectMainpageBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.bindChips
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.currentSpan
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.loadHomepageList
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.updateChips
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.SearchRanker
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import java.util.Locale

class SearchFragment : BaseFragment<FragmentSearchBinding>(
    BaseFragment.BindingCreator.Bind(FragmentSearchBinding::bind)
) {
    companion object {
        // NOIR perf: semua Regex di-compile SEKALI. Sebelumnya pattern di-compile
        // per item per render (noirNormName/noirQualityScore) — dengan beberapa
        // provider × ratusan hasil dan render ulang tiap provider selesai, ini
        // berarti puluhan ribu compile di main thread = "ngelag parah pas search".
        private val RX_PAREN = Regex("\\([^)]*\\)")
        private val RX_NON_ALNUM = Regex("[^a-z0-9\\s]")
        private val RX_YEAR = Regex("\\b(19|20)\\d{2}\\b")
        private val RX_WS = Regex("\\s+")
        private val RX_4K = Regex("4k|2160p|uhd")
        private val RX_BR = Regex("bluray|blu-ray|brrip|bdrip|remux|1080")
        private val RX_HD = Regex("720p|hdtv|webrip|web-dl")
        private val RX_SD = Regex("\\bsd\\b|dvdrip|dvd|cam|telesync")

        fun List<SearchResponse>.filterSearchResponse(): List<SearchResponse> {
            return this.filter { response ->
                if (response is AnimeSearchResponse) {
                    val status = response.dubStatus
                    (status.isNullOrEmpty()) || (status.any {
                        APIRepository.dubStatusActive.contains(it)
                    })
                } else {
                    true
                }
            }
        }

        const val SEARCH_QUERY = "search_query"

        fun newInstance(query: String): Bundle {
            return Bundle().apply {
                if (query.isNotBlank()) putString(SEARCH_QUERY, query)
            }
        }
    }

    private val searchViewModel: SearchViewModel by activityViewModels()
    private var bottomSheetDialog: BottomSheetDialog? = null

    private val speechRecognizerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    binding?.mainSearch?.setQuery(recognizedText, true)
                }
            }
        }

    override fun pickLayout(): Int? =
        if (isLayout(TV or EMULATOR)) R.layout.fragment_search_tv else R.layout.fragment_search

    // === NOIR: mode render pencarian ===
    private val noirSuggestHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var noirMergedMode = true

    // === NOIR fix16: kelompok kualitas + ranking relevansi ===
    // 0 = Semua, 1 = Blu-ray/4K ke atas, 2 = HD, 3 = SD
    private var noirQualityBucket = 0
    private val noirBucketLabels = listOf("Semua", "Blu-ray · 4K+", "HD", "SD")

    /** Judul ternormalisasi: tanpa tanda baca/kurung/tahun — "Oppenheimer (4K)" == "oppenheimer". */
    private fun noirNormName(s: String): String {
        val base = s.lowercase().replace(RX_PAREN, " ").replace(RX_NON_ALNUM, " ")
        val noYear = base.replace(RX_YEAR, " ").replace(RX_WS, " ").trim()
        // NOIR fix akurasi: judul yang MEMANG berupa tahun ("1917", "2012")
        // jangan sampai jadi string kosong — dulu semua judul-tahun ter-dedup
        // menjadi satu kartu karena key-nya sama-sama "".
        return if (noYear.isEmpty()) base.replace(RX_WS, " ").trim() else noYear
    }

    /** Skor kelas kualitas: 4 = 4K, 3 = Blu-ray/HDR/1080, 2 = HD, 1 = SD/cam, 0 = tak dikenal. */
    private fun noirQualityScore(r: SearchResponse): Int {
        val q = r.quality
        val n = r.name.lowercase()
        if (q == SearchQuality.FourK || q == SearchQuality.UHD ||
            RX_4K.containsMatchIn(n)
        ) return 4
        if (q == SearchQuality.BlueRay || q == SearchQuality.HDR ||
            RX_BR.containsMatchIn(n)
        ) return 3
        if (q == SearchQuality.HD || q == SearchQuality.HQ ||
            RX_HD.containsMatchIn(n)
        ) return 2
        if (q == SearchQuality.SD || RX_SD.containsMatchIn(n)) return 1
        return 0
    }

    /** Kartu musik internal Noir (SoundCloud/Qobuz/TL dl) — lintas tipe & bucket. */
    private fun isNoirMusic(r: SearchResponse): Boolean =
        r.url.startsWith("noirsc://") || r.url.startsWith("noirqz://") || r.url.startsWith("noirtl://")

    private fun noirBucketMatches(r: SearchResponse): Boolean {
        // Bucket kualitas film tidak boleh menyembunyikan hasil musik.
        if (isNoirMusic(r)) return true
        val s = noirQualityScore(r)
        return when (noirQualityBucket) {
            1 -> s >= 3           // Blu-ray / 4K / HDR ke atas
            2 -> s == 2           // HD
            3 -> s == 1           // SD
            else -> true
        }
    }

    /** Chip kelompok kualitas — dibangun programatik agar ikut tema monokrom. */
    private fun noirBuildQualityChips() {
        val row = binding?.noirQualityChips ?: return
        val ctx = row.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        row.removeAllViews()
        noirBucketLabels.forEachIndexed { i, label ->
            val selected = i == noirQualityBucket
            val chip = TextView(ctx).apply {
                text = label
                textSize = 12.5f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    if (selected) {
                        setColor(0xFFFFFFFF.toInt())
                    } else {
                        setColor(0x00000000)
                        setStroke(dp(1), 0x66FFFFFF)
                    }
                }
                setTextColor(if (selected) 0xFF111111.toInt() else 0xFFAAAAAA.toInt())
                isAllCaps = false
                setOnClickListener { v ->
                    com.lagradost.cloudstream3.utils.NoirHaptic.tap(v)
                    if (noirQualityBucket != i) {
                        noirQualityBucket = i
                        noirBuildQualityChips()
                        // respons instan untuk aksi eksplisit user (delay 0)
                        searchViewModel.currentSearch.value?.let { requestRenderSearch(it, 0) }
                    }
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            row.addView(chip, lp)
        }
    }
    // === akhir NOIR fix16 ===

    // NOIR fix: empty state — kueri kosong + riwayat kosong sebelumnya = layar
    // hitam polos (terlihat seperti crash). Tampilkan panduan + trending.
    private fun updateSearchEmptyState(history: List<SearchHistoryItem>) {
        val b = binding ?: return
        val queryBlank = b.mainSearch.query.isNullOrBlank()
        val showEmpty = queryBlank && history.isEmpty()
        b.noirSearchEmpty.isVisible = showEmpty
        if (showEmpty) {
            // jangan dua area weight=1 tampil bersamaan (layar terbelah)
            b.searchHistoryRecycler.isVisible = false
            searchViewModel.fetchTrending()
        } else if (queryBlank) {
            b.searchHistoryRecycler.isVisible = true
        }
    }

    private fun updateMergeToggleIcon() {
        binding?.noirMergeToggle?.apply {
            setImageResource(
                if (noirMergedMode) R.drawable.baseline_grid_view_24
                else R.drawable.baseline_list_alt_24
            )
            alpha = if (noirMergedMode) 1f else 0.55f
        }
    }

    /**
     * NOIR: render hasil pencarian. Dua mode (toggle di toolbar):
     *  - gabungan (default): SATU baris ter-dedup lintas provider = rapi,
     *    satu kartu per judul (beranda tetap selalu mode ini);
     *  - per-provider: baris asli per penyedia (perilaku bawaan).
     */
    // NOIR perf: render di-coalesce + komputasi berat dipindah ke background.
    // Sebelumnya SELURUH pipeline (filter, dedup, sort, regex) jalan sinkron di
    // main thread, dan dipanggil ULANG setiap satu provider selesai (= berkali-
    // kali per pencarian). Ini akar "ngelag parah pas load data search".
    private val noirRenderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var noirRenderJob: Job? = null

    /** Minta render; burst penyelesaian provider di-coalesce [delayMs]. */
    private fun requestRenderSearch(
        map: Map<String, ExpandableSearchList>,
        delayMs: Long = 120L,
    ) {
        noirRenderHandler.removeCallbacksAndMessages(null)
        noirRenderHandler.postDelayed({ computeAndSubmitSearch(map) }, delayMs)
    }

    private fun computeAndSubmitSearch(map: Map<String, ExpandableSearchList>) {
        // Bacaan ringan di main thread; kerja berat di background.
        val ctx = context ?: return
        val mergedLabel = ctx.getString(R.string.noir_merged_results)
        val hiddenQualities = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getStringSet(ctx.getString(R.string.pref_filter_search_quality_key), setOf())
            ?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val typeFilter = selectedSearchTypes.toSet()
        val queryText = searchViewModel.lastQuery.orEmpty()
        val merged = noirMergedMode

        noirRenderJob?.cancel()
        noirRenderJob = ioSafe {
            try {
                val newItems = buildRenderItems(
                    map, mergedLabel, hiddenQualities, typeFilter, queryText, merged
                )
                withContext(Dispatchers.Main) {
                    // NOIR: binding nullable di level kelas — wajib safe call.
                    (binding?.searchMasterRecycler?.adapter as? ParentItemAdapter)?.apply {
                        submitList(newItems)
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    /**
     * NOIR: build baris hasil pencarian. Dua mode (toggle di toolbar):
     *  - gabungan (default): SATU baris ter-dedup lintas provider = rapi,
     *    satu kartu per judul;
     *  - per-provider: baris asli per penyedia (perilaku bawaan).
     */
    private fun buildRenderItems(
        map: Map<String, ExpandableSearchList>,
        mergedLabel: String,
        hiddenQualities: List<Int>,
        typeFilter: Set<TvType>,
        queryText: String,
        merged: Boolean,
    ): List<HomeViewModel.ExpandableHomepageList> {
        val pinnedOrder = DataStoreHelper.pinnedProviders.reversedArray()

        val sortedList = map.toList().sortedWith(compareBy { (providerName, _) ->
            val index = pinnedOrder.indexOf(providerName)
            if (index == -1) Int.MAX_VALUE else index
        })

        // NOIR perf: memoisasi normalisasi & skor kualitas — satu kali per item
        // per render (dulu dihitung berulang kali per item: dedup + sort).
        val normCache = HashMap<String, String>()
        val qualCache = HashMap<String, Int>()
        fun normOf(r: SearchResponse): String =
            normCache.getOrPut(r.url) { noirNormName(r.name) }
        fun qualOf(r: SearchResponse): Int =
            qualCache.getOrPut(r.url) { noirQualityScore(r) }

        // NOIR akurasi: hormati chip tipe (Movie/Seri TV/...) pada HASIL, bukan
        // cuma pada provider — sebelumnya hasil YouTube lolos ke pencarian film.
        // Kartu musik Noir lolos karena fitur lintas tipe.
        fun keep(r: SearchResponse): Boolean {
            if (hiddenQualities.isNotEmpty() &&
                hiddenQualities.contains(r.quality?.ordinal ?: -1)
            ) return false
            if (!noirBucketMatches(r)) return false
            if (typeFilter.isNotEmpty() && !isNoirMusic(r) && !typeFilter.contains(r.type)) {
                return false
            }
            return true
        }

        // NOIR akurasi: ranking fuzzy (Levenshtein.partialRatio) sehingga tahan
        // salah ketik ("narutp" -> "Naruto"); kualitas hanya pemecah seri.
        fun scoreOf(r: SearchResponse): Int =
            SearchRanker.relevanceScore(queryText, normOf(r)) * 10 + qualOf(r)

        val newItems = if (merged) {
            val all = sortedList.flatMap { (_, data) -> data.list }
            val filtered = all.filter(::keep)
            // NOIR fix16: dedup pakai judul ternormalisasi — "Title (4K)"
            // dan "Title" jadi SATU kartu; salinan berkualitas tertinggi menang.
            val seen = LinkedHashMap<String, SearchResponse>()
            filtered.forEach { r ->
                val key = normOf(r) + "|" + (r.type?.name ?: "")
                val prev = seen[key]
                if (prev == null || qualOf(r) > qualOf(prev)) {
                    seen[key] = r
                }
            }
            val deduped = seen.values.sortedByDescending { scoreOf(it) }
            // NOIR fix18: isi antrean sesi player musik (prev/next) dari
            // hasil "Noir Music" yang sedang tampil.
            val musicCards = deduped.filter(::isNoirMusic)
            if (musicCards.isNotEmpty()) {
                com.lagradost.cloudstream3.ui.music.NoirMusicPlayerActivity
                    .fillQueue(musicCards)
            }
            listOf(
                HomeViewModel.ExpandableHomepageList(
                    HomePageList(mergedLabel, deduped),
                    1,
                    false
                )
            )
        } else {
            sortedList.map { (providerName, providerData) ->
                val dataListFiltered = providerData.list.filter(::keep)
                    .sortedByDescending { scoreOf(it) }

                HomeViewModel.ExpandableHomepageList(
                    HomePageList(providerName, dataListFiltered),
                    providerData.currentPage,
                    providerData.hasNext
                )
            }
        }

        // NOIR fix18: antrean tetap benar pada mode gabungan maupun per-provider.
        if (!merged) {
            val musicQueue = sortedList.flatMap { (_, data) -> data.list }
                .filter(::isNoirMusic)
                .distinctBy { it.url }
            if (musicQueue.isNotEmpty()) {
                com.lagradost.cloudstream3.ui.music.NoirMusicPlayerActivity
                    .fillQueue(musicQueue)
            }
        }
        return newItems
    }
    // === akhir NOIR ===

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        bottomSheetDialog?.ownShow()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        hideKeyboard()
        bottomSheetDialog?.ownHide()
        activity?.detachBackPressedCallback("SearchFragment")
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        afterPluginsLoadedEvent += ::reloadRepos
    }

    override fun onStop() {
        super.onStop()
        afterPluginsLoadedEvent -= ::reloadRepos
    }

    var selectedSearchTypes = mutableListOf<TvType>()
    var selectedApis = mutableSetOf<String>()

    /**
     * Will filter all providers by preferred media and selectedSearchTypes.
     * If that results in no available providers then only filter
     * providers by preferred media
     **/
    fun search(query: String?) {
        if (query == null) return
        // don't resume state from prev search
        (binding?.searchMasterRecycler?.adapter as? BaseAdapter<*, *>)?.clearState()
        context?.let { ctx ->
            val default = enumValues<TvType>().sorted().filter { it != TvType.NSFW }
                .map { it.ordinal.toString() }.toSet()
            val preferredTypes = (PreferenceManager.getDefaultSharedPreferences(ctx)
                .getStringSet(this.getString(R.string.prefer_media_type_key), default)
                ?.ifEmpty { default } ?: default)
                .mapNotNull { it.toIntOrNull() ?: return@mapNotNull null }

            val settings = ctx.getApiSettings()

            // NOIR fix (akurasi): bila user belum pernah memilih provider secara
            // eksplisit (bottom-sheet filter), selectedApis KOSONG sehingga
            // providersActive kosong = SEMUA provider dicari dan chip tipe
            // (Movie/Seri TV/...) tidak berpengaruh — itulah kenapa hasil
            // YouTube muncul saat mencari film. Basisnya harus semua provider
            // valid, baru kemudian disaring oleh chip tipe.
            val baseApis = selectedApis.ifEmpty {
                ctx.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
                    .map { it.name }
                    .toSet()
            }

            // NOIR: YouTube adalah platform video singkat, bukan sumber film.
            // Kecuali pengguna memilih providernya sendiri lewat filter, semua
            // provider YouTube dikeluarkan dari basis pencarian supaya hasil
            // pencarian film tidak tercampur konten YouTube.
            val baseApisFinal = if (selectedApis.isEmpty()) {
                baseApis.filterNot { it.contains("youtube", ignoreCase = true) }.toSet()
            } else baseApis

            val notFilteredBySelectedTypes = baseApisFinal.filter { name ->
                settings.contains(name)
            }.map { name ->
                name to getApiFromNameNull(name)?.supportedTypes
            }.filter { (_, types) ->
                types?.any { preferredTypes.contains(it.ordinal) } == true
            }

            searchViewModel.searchAndCancel(
                query = query,
                providersActive = notFilteredBySelectedTypes.filter { (_, types) ->
                    types?.any { selectedSearchTypes.contains(it) } == true
                }.ifEmpty { notFilteredBySelectedTypes }.map { it.first }.toSet()
            )
        }
    }

    // Null if defined as a variable
    // This needs to be run after view created

    private fun reloadRepos(success: Boolean = false) = main {
        searchViewModel.reloadRepos()
        context?.filterProviderByPreferredMedia()?.let { validAPIs ->
            bindChips(
                binding?.tvtypesChipsScroll?.tvtypesChips,
                selectedSearchTypes,
                validAPIs.flatMap { api -> api.supportedTypes }.distinct()
            ) { list ->
                if (selectedSearchTypes.toSet() != list.toSet()) {
                    DataStoreHelper.searchPreferenceTags = list
                    selectedSearchTypes.clear()
                    selectedSearchTypes.addAll(list)
                    search(binding?.mainSearch?.query?.toString())
                }
            }
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )

        // Fix grid
        currentSpan = view.context.getSpanCount()
        binding?.searchAutofitResults?.spanCount = currentSpan
        HomeFragment.configEvent.invoke()
    }

    override fun onBindingCreated(
        binding: FragmentSearchBinding,
        savedInstanceState: Bundle?
    ) {
        // NOIR: aura ambient indigo+teal (palet aurora expert) di search.
        binding.noirAuraSearch.setColors(0xFF6366F1.toInt(), 0xFF2DD4BF.toInt())

        reloadRepos()
        binding.apply {
            val adapter =
                SearchAdapter(
                    searchAutofitResults,
                ) { callback ->
                    SearchHelper.handleSearchClickCallback(callback)
                }

            searchRoot.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.tag =
                "tv_no_focus_tag"
            searchAutofitResults.setRecycledViewPool(SearchAdapter.sharedPool)
            searchAutofitResults.adapter = adapter
            searchLoadingBar.alpha = 0f
        }

        binding.voiceSearch.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                try {
                    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                        showToast(R.string.speech_recognition_unavailable)
                    } else {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(
                                RecognizerIntent.EXTRA_PROMPT,
                                ctx.getString(R.string.begin_speaking)
                            )
                        }
                        speechRecognizerLauncher.launch(intent)
                    }
                } catch (_: Throwable) {
                    // launch may throw
                    showToast(R.string.speech_recognition_unavailable)
                }
            }
        }

        val searchExitIcon =
            binding.mainSearch.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)

        selectedApis = DataStoreHelper.searchPreferenceProviders.toMutableSet()

        binding.searchFilter.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                val validAPIs = ctx.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
                var currentValidApis = listOf<MainAPI>()
                val currentSelectedApis = if (selectedApis.isEmpty()) validAPIs.map { it.name }
                    .toMutableSet() else selectedApis

                val builder =
                    BottomSheetDialog(ctx)

                builder.behavior.state = BottomSheetBehavior.STATE_EXPANDED

                val selectMainpageBinding: HomeSelectMainpageBinding =
                    HomeSelectMainpageBinding.inflate(
                        builder.layoutInflater,
                        null,
                        false
                    )
                builder.setContentView(selectMainpageBinding.root)
                builder.show()
                builder.let { dialog ->
                    val previousSelectedApis = selectedApis.toSet()
                    val previousSelectedSearchTypes = selectedSearchTypes.toSet()

                    val isMultiLang = ctx.getApiProviderLangSettings().let { set ->
                        set.size > 1 || set.contains(AllLanguagesName)
                    }

                    val cancelBtt = dialog.findViewById<MaterialButton>(R.id.cancel_btt)
                    val applyBtt = dialog.findViewById<MaterialButton>(R.id.apply_btt)

                    val listView = dialog.findViewById<ListView>(R.id.listview1)
                    val arrayAdapter = ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)
                    listView?.adapter = arrayAdapter
                    listView?.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

                    listView?.setOnItemClickListener { _, _, i, _ ->
                        if (currentValidApis.isNotEmpty()) {
                            val api = currentValidApis[i].name
                            if (currentSelectedApis.contains(api)) {
                                listView.setItemChecked(i, false)
                                currentSelectedApis -= api
                            } else {
                                listView.setItemChecked(i, true)
                                currentSelectedApis += api
                            }
                        }
                    }

                    fun updateList(types: List<TvType>) {
                        DataStoreHelper.searchPreferenceTags = types

                        arrayAdapter.clear()
                        currentValidApis = validAPIs.filter { api ->
                            api.supportedTypes.any {
                                types.contains(it)
                            }
                        }.sortedBy { it.name.lowercase() }

                        val names = currentValidApis.map {
                            if (isMultiLang) "${
                                SubtitleHelper.getFlagFromIso(
                                    it.lang
                                )?.plus(" ") ?: ""
                            }${it.name}" else it.name
                        }
                        for ((index, api) in currentValidApis.map { it.name }.withIndex()) {
                            listView?.setItemChecked(index, currentSelectedApis.contains(api))
                        }

                        //arrayAdapter.notifyDataSetChanged()
                        arrayAdapter.addAll(names)
                        arrayAdapter.notifyDataSetChanged()
                    }

                    bindChips(
                        selectMainpageBinding.tvtypesChipsScroll.tvtypesChips,
                        selectedSearchTypes,
                        validAPIs.flatMap { api -> api.supportedTypes }.distinct()
                    ) { list ->
                        updateList(list)

                        // refresh selected chips in main chips
                        if (selectedSearchTypes.toSet() != list.toSet()) {
                            selectedSearchTypes.clear()
                            selectedSearchTypes.addAll(list)
                            updateChips(
                                binding.tvtypesChipsScroll.tvtypesChips,
                                selectedSearchTypes
                            )

                        }
                    }


                    cancelBtt?.setOnClickListener {
                        dialog.dismissSafe()
                    }

                    cancelBtt?.setOnClickListener {
                        dialog.dismissSafe()
                    }

                    applyBtt?.setOnClickListener {
                        //if (currentApiName != selectedApiName) {
                        //    currentApiName?.let(callback)
                        //}
                        dialog.dismissSafe()
                    }

                    dialog.setOnDismissListener {
                        DataStoreHelper.searchPreferenceProviders = currentSelectedApis.toList()
                        selectedApis = currentSelectedApis

                        // run search when dialog is close
                        if (previousSelectedApis != selectedApis.toSet() || previousSelectedSearchTypes != selectedSearchTypes.toSet()) {
                            search(binding.mainSearch.query.toString())
                        }
                    }
                    updateList(selectedSearchTypes.toList())
                }
            }
        }

        val settingsManager = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        val isAdvancedSearch = settingsManager?.getBoolean("advanced_search", true) ?: true
        val isSearchSuggestionsEnabled = settingsManager?.getBoolean("search_suggestions_enabled", true) ?: true

        selectedSearchTypes = DataStoreHelper.searchPreferenceTags.toMutableList()

        if (!isLayout(PHONE)) {
            binding.searchFilter.isFocusable = true
            binding.searchFilter.isFocusableInTouchMode = true
        }
        
        // Hide suggestions when search view loses focus (phone only)
        if (isLayout(PHONE)) {
            binding.mainSearch.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    searchViewModel.clearSuggestions()
                }
            }
        }


        binding.mainSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                search(query)
                searchViewModel.clearSuggestions()

                binding.mainSearch.let {
                    hideKeyboard(it)
                }

                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                //searchViewModel.quickSearch(newText)
                val showHistory = newText.isBlank()
                if (showHistory) {
                    searchViewModel.clearSearch()
                    searchViewModel.updateHistory()
                    searchViewModel.clearSuggestions()
                } else {
                    // NOIR: debounce 300ms (panduan aikais) — jangan spam
                    // network tiap ketikan; kirim setelah user berhenti mengetik.
                    noirSuggestHandler.removeCallbacksAndMessages(null)
                    noirSuggestHandler.postDelayed({
                        if (isSearchSuggestionsEnabled) {
                            searchViewModel.fetchSuggestions(newText)
                        }
                    }, 300L)
                }
                binding.apply {
                    searchHistoryRecycler.isVisible = showHistory
                    searchMasterRecycler.isVisible = !showHistory && isAdvancedSearch
                    searchAutofitResults.isVisible = !showHistory && !isAdvancedSearch
                    // Hide suggestions when showing history or showing search results
                    searchSuggestionsRecycler.isVisible = !showHistory && isSearchSuggestionsEnabled
                }
                // NOIR fix: sinkronkan empty state (hitam polos) tiap ketikan.
                updateSearchEmptyState(searchViewModel.currentHistory.value ?: emptyList())

                return true
            }
        })



        observe(searchViewModel.searchResponse) {
            when (it) {
                is Resource.Success -> {
                    it.value.let { data ->
                        val list = data.list
                        if (list.isNotEmpty()) {
                            (binding.searchAutofitResults.adapter as? SearchAdapter)?.submitList(
                                list
                            )
                        }
                    }
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Failure -> {
                    // Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Loading -> {
                    searchExitIcon?.alpha = 0f
                    binding.searchLoadingBar.alpha = 1f
                }
            }
        }

        // NOIR: toggle gabungan/per-provider + render terpusat.
        noirMergedMode = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(getString(R.string.noir_search_merged_key), true)
        binding.noirMergeToggle.setOnClickListener { v ->
            com.lagradost.cloudstream3.utils.NoirHaptic.tap(v)
            noirMergedMode = !noirMergedMode
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean(getString(R.string.noir_search_merged_key), noirMergedMode)
                .apply()
            updateMergeToggleIcon()
            searchViewModel.currentSearch.value?.let { requestRenderSearch(it, 0) }
        }
        updateMergeToggleIcon()

        // NOIR fix16: chip kelompok kualitas.
        noirBuildQualityChips()

        observe(searchViewModel.currentSearch) { list ->
            // NOIR perf: coalesce burst (satu provider selesai = satu event).
            requestRenderSearch(list)
        }


        /*main_search.setOnQueryTextFocusChangeListener { _, b ->
            if (b) {
                // https://stackoverflow.com/questions/12022715/unable-to-show-keyboard-automatically-in-the-searchview
                showInputMethod(view.findFocus())
            }
        }*/
        //main_search.onActionViewExpanded()*/

        val masterAdapter =
            ParentItemAdapter(id = "masterAdapter".hashCode(), { callback ->
                SearchHelper.handleSearchClickCallback(callback)
            }, { item ->
                bottomSheetDialog = activity?.loadHomepageList(item, dismissCallback = {
                    bottomSheetDialog = null
                }, expandCallback = { name -> searchViewModel.expandAndReturn(name) })
            }, expandCallback = { name ->
                ioSafe {
                    searchViewModel.expandAndReturn(name)
                }
            })

        val historyAdapter = SearchHistoryAdaptor { click ->
            val searchItem = click.item
            when (click.clickAction) {
                SEARCH_HISTORY_OPEN -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    searchViewModel.clearSearch()
                    if (searchItem.type.isNotEmpty())
                        updateChips(
                            binding.tvtypesChipsScroll.tvtypesChips,
                            searchItem.type.toMutableList()
                        )
                    binding.mainSearch.setQuery(searchItem.searchText, true)
                }

                SEARCH_HISTORY_REMOVE -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    removeKey("$currentAccount/$SEARCH_HISTORY_KEY", searchItem.key)
                    searchViewModel.updateHistory()
                }
                
                SEARCH_HISTORY_CLEAR -> {
                    // Show confirmation dialog (from footer button)
                    activity?.let { ctx ->
                        val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
                        val dialogClickListener =
                            DialogInterface.OnClickListener { _, which ->
                                when (which) {
                                    DialogInterface.BUTTON_POSITIVE -> {
                                        removeKeys("$currentAccount/$SEARCH_HISTORY_KEY")
                                        searchViewModel.updateHistory()
                                    }

                                    DialogInterface.BUTTON_NEGATIVE -> {
                                    }
                                }
                            }

                        try {
                            builder.setTitle(R.string.clear_history).setMessage(
                                ctx.getString(R.string.delete_message).format(
                                    ctx.getString(R.string.history)
                                )
                            )
                                .setPositiveButton(R.string.sort_clear, dialogClickListener)
                                .setNegativeButton(R.string.cancel, dialogClickListener)
                                .show().setDefaultFocus()
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }

                else -> {
                    // wth are you doing???
                }
            }
        }

        val suggestionAdapter = SearchSuggestionAdapter { callback ->
            when (callback.clickAction) {
                SEARCH_SUGGESTION_CLICK -> {
                    // Search directly
                    binding.mainSearch.setQuery(callback.suggestion, true)
                    searchViewModel.clearSuggestions()
                }
                SEARCH_SUGGESTION_FILL -> {
                    // Fill the search box without searching
                    binding.mainSearch.setQuery(callback.suggestion, false)
                }
                SEARCH_SUGGESTION_CLEAR -> {
                    // Clear suggestions (from footer button)
                    searchViewModel.clearSuggestions()
                }
            }
        }

        binding.apply {
            searchHistoryRecycler.adapter = historyAdapter
            searchHistoryRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)
            //searchHistoryRecycler.layoutManager = GridLayoutManager(context, 1)

            // Setup suggestions RecyclerView
            searchSuggestionsRecycler.adapter = suggestionAdapter
            searchSuggestionsRecycler.layoutManager = LinearLayoutManager(context)

            searchMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            searchMasterRecycler.adapter = masterAdapter
            //searchMasterRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)

            searchMasterRecycler.layoutManager = GridLayoutManager(context, 1)

            // Automatically search the specified query, this allows the app search to launch from intent
            var sq =
                arguments?.getString(SEARCH_QUERY) ?: savedInstanceState?.getString(SEARCH_QUERY)
            if (sq.isNullOrBlank()) {
                sq = MainActivity.nextSearchQuery
            }

            sq?.let { query ->
                if (query.isBlank()) return@let
                mainSearch.setQuery(query, true)
                // Clear the query as to not make it request the same query every time the page is opened
                arguments?.remove(SEARCH_QUERY)
                savedInstanceState?.remove(SEARCH_QUERY)
                MainActivity.nextSearchQuery = null
            }
        }

        observe(searchViewModel.currentHistory) { list ->
            (binding.searchHistoryRecycler.adapter as? SearchHistoryAdaptor?)?.submitList(list)
             // Scroll to top to show newest items (list is sorted by newest first)
            if (list.isNotEmpty()) {
                binding.searchHistoryRecycler.scrollToPosition(0)
            }
            updateSearchEmptyState(list)
        }

        // NOIR fix: isi empty state dengan judul trending; klik = langsung cari.
        observe(searchViewModel.trending) { items ->
            val b = binding ?: return@observe
            val hasItems = items.isNotEmpty()
            b.noirTrendingTitle.isVisible = hasItems
            val holder = b.noirTrendingHolder
            holder.removeAllViews()
            if (!hasItems) return@observe
            val density = holder.context.resources.displayMetrics.density
            items.forEach { title ->
                holder.addView(
                    TextView(holder.context).apply {
                        text = title
                        textSize = 14f
                        setTextColor(0xFFE5E5E7.toInt())
                        setPadding(0, (9 * density).toInt(), 0, (9 * density).toInt())
                        setOnClickListener { v ->
                            com.lagradost.cloudstream3.utils.NoirHaptic.tap(v)
                            binding?.mainSearch?.setQuery(title, true)
                        }
                    }
                )
            }
        }

        // Observe search suggestions
        observe(searchViewModel.searchSuggestions) { suggestions ->
            val hasSuggestions = suggestions.isNotEmpty()
            binding.searchSuggestionsRecycler.isVisible = hasSuggestions
            // NOIR FIX: saran menempel persis di bawah header (bar+chip),
            // tidak ngambang di tengah layar seperti margin 100dp dahulu.
            if (hasSuggestions) {
                binding.searchSuggestionsRecycler.post {
                    val hdr = binding.noirSearchHeader
                    val lp = binding.searchSuggestionsRecycler.layoutParams
                        as? android.widget.FrameLayout.LayoutParams ?: return@post
                    if (lp.topMargin != hdr.height && hdr.height > 0) {
                        lp.topMargin = hdr.height
                        binding.searchSuggestionsRecycler.layoutParams = lp
                    }
                }
            }
            (binding.searchSuggestionsRecycler.adapter as? SearchSuggestionAdapter?)?.submitList(suggestions)
            
            // On non-phone layouts, redirect focus and handle back button
            if (!isLayout(PHONE)) {
                if (hasSuggestions) {
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId = R.id.search_suggestions_recycler
                    // Attach back button callback to clear suggestions
                    activity?.attachBackPressedCallback("SearchFragment") {
                        searchViewModel.clearSuggestions()
                    }
                } else {
                    // Reset to default focus target (history)
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId = R.id.search_history_recycler
                    // Detach back button callback when no suggestions
                    activity?.detachBackPressedCallback("SearchFragment")
                }
            }
        }

        searchViewModel.updateHistory()
    }
}
