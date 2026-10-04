package com.lagradost.cloudstream3.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentHomeBinding
import com.lagradost.cloudstream3.databinding.HomeEpisodesExpandedBinding
import com.lagradost.cloudstream3.databinding.HomeSelectMainpageBinding
import com.lagradost.cloudstream3.databinding.TvtypesChipsBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountSelectLinear
import com.lagradost.cloudstream3.ui.account.AccountViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper.handleSearchClickCallback
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.isNetworkAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.isRecyclerScrollable
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.EmptyEvent
import com.lagradost.cloudstream3.utils.SubtitleHelper.getFlagFromIso
import com.lagradost.cloudstream3.utils.TvChannelUtils
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.popupMenuNoIconsAndNoStringRes
import com.lagradost.cloudstream3.utils.UIHelper.toPx

private const val TAG = "HomeFragment"

class HomeFragment : BaseFragment<FragmentHomeBinding>(
    BindingCreator.Bind(FragmentHomeBinding::bind)
) {
    private var noirHeroItem: com.lagradost.cloudstream3.SearchResponse? = null
    private var noirScrollWired = false

    // NOIR inclusivity (prinsip aplikasi peraih award): hormati setting sistem
    // "remove animations" — parallax & drift aura dimatikan bila aktif.
    // NOIR: gerak mati bila sistem reduce-motion ATAU user menyetel
    // "Matikan Semua Animasi" di pengaturan Noir.
    private val noirReduceMotion: Boolean by lazy {
        com.lagradost.cloudstream3.utils.NoirTuning.animOff ||
        try {
            android.provider.Settings.Global.getFloat(
                requireContext().contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (_: Throwable) {
            false
        }
    }

    private var noirNavHidden = false

    // NOIR variasi: carousel hero — judul unggulan berotasi otomatis tiap 8
    // detik (crossfade opacity saja = murah), ala billboard Apple TV. Dipakai
    // view.postDelayed sehingga otomatis berhenti saat view detached.
    private var noirHeroCandidates: List<com.lagradost.cloudstream3.SearchResponse> =
        emptyList()
    private var noirHeroIndex = 0

    private fun scheduleNoirHeroRotation(b: FragmentHomeBinding) {
        if (noirReduceMotion) return
        b.noirHero.removeCallbacks(noirHeroRotateRunnable)
        b.noirHero.postDelayed(noirHeroRotateRunnable, 8000)
    }

    private val noirHeroRotateRunnable = Runnable {
        val b = binding ?: return@Runnable
        if (noirHeroCandidates.size < 2) return@Runnable
        noirHeroIndex = (noirHeroIndex + 1) % noirHeroCandidates.size
        val next = noirHeroCandidates[noirHeroIndex]
        noirHeroItem = next
        b.noirHeroImg.animate().alpha(0f).setDuration(180).withEndAction {
            val bb = binding ?: return@withEndAction
            bb.noirHeroTitle.text = next.name
            // NOIR (Zeigarnik): meta slide juga menampilkan progres tontonan.
            val pdn = DataStoreHelper.getViewPos(next.id)
            bb.noirHeroMeta.text = listOfNotNull(
                next.type?.name,
                next.apiName,
                pdn?.takeIf { it.duration > 60_000L }?.let {
                    "${((it.position.toFloat() / it.duration) * 100f).toInt().coerceIn(0, 100)}% ditonton"
                }
            ).joinToString(", ")
            bb.noirHeroImg.loadImage(next.posterUrl) { size(512) }
            bb.noirAura.applyFromUrl(next.posterUrl)
            bb.noirHeroPlay.setOnClickListener { v ->
                com.lagradost.cloudstream3.utils.NoirHaptic.tap(v)
                activity?.loadSearchResult(next)
            }
            bb.noirHeroImg.translationY = 0f
            bb.noirHeroTitle.alpha = 1f
            bb.noirHeroMeta.alpha = 0.8f
            bb.noirHeroImg.scaleX = 1.05f
            bb.noirHeroImg.scaleY = 1.05f
            bb.noirHeroImg.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(420).start()
        }.start()
        scheduleNoirHeroRotation(b)
    }

    // NOIR immersion: tab bar menyelam saat scroll ke bawah dan muncul lagi
    // saat ke atas — konten dapat ruang penuh, mata fokus ke poster.
    private fun noirSetNavHidden(hidden: Boolean) {
        if (noirNavHidden == hidden) return
        val nav = activity?.findViewById<android.view.View>(R.id.nav_view) ?: return
        noirNavHidden = hidden
        nav.animate()
            .translationY(if (hidden) nav.height.toFloat() else 0f)
            .alpha(if (hidden) 0f else 1f)
            .setDuration(if (noirReduceMotion) 0 else 220)
            .start()
    }

    // NOIR: hero banner besar — item pertama berposter jadi unggulan;
    // margin recycler menyesuaikan supaya baris mulai tepat di bawah hero.
    private fun noirUpdateHero(page: Map<String, HomeViewModel.ExpandableHomepageList>?) {
        val b = binding ?: return
        // NOIR: hero FULL-BLEED (permintaan pengguna) — menempel ke keempat
        // tepi layar tanpa margin & tanpa sudut membulat; scrim internal
        // menjaga keterbacaan judul, aura meleleh di batas bawahnya.
        b.noirHero.clipToOutline = false
        // NOIR: hero full-bleed yang LELEH ke latar (tanpa kartu/margin/elevasi/
        // radius) — satu permukaan kontinu bersama aura & carousel transparan.
        val item = page?.values?.asSequence()
            ?.flatMap { it.list.list }
            ?.firstOrNull { !it.posterUrl.isNullOrEmpty() && it.name.isNotBlank() }
        // NOIR FIX: topMargin tidak menge-clip — saat scroll, baris ikut
        // tergambar MENIMPA banner hero ("yang lain ketimpa banner").
        // Padding ATAS + clipToPadding = baris mulai di bawah hero dan rapi
        // di batas hero saat scroll. Padding BAWAH = 0: poster harus FULL
        // sampai tepi bawah layar (permintaan pengguna) — dock kaca mengambang
        // di atasnya dan ikut menyelam saat scroll ke bawah.
        val hidden = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(b.root.context)
            .getBoolean("noir_hero_hidden", false)
        val heroPad = when {
            item == null -> 0
            hidden -> noirHiddenTopPad()
            else -> (400 * resources.displayMetrics.density).toInt()
        }
        b.homeMasterRecycler.clipToPadding = true
        b.homeMasterRecycler.setPadding(
            b.homeMasterRecycler.paddingLeft, heroPad,
            b.homeMasterRecycler.paddingRight, 0
        )
        // NOIR: chevron kaca hero dipindah ke posisi yang gampang diklik.
        noirPositionHeroToggle(b, hidden)
        if (item == null || hidden) {
            b.noirHero.isVisible = false
            if (item == null) return
        }
        noirHeroItem = item
        b.noirHero.isVisible = true
        b.noirHeroTitle.text = item.name
        // NOIR reveal: masuk dari blur-skala 1.05 -> 1 (sekali per item).
        if (!noirReduceMotion) {
            b.noirHeroImg.animate().cancel()
            b.noirHeroImg.alpha = 0f
            b.noirHeroImg.scaleX = 1.05f
            b.noirHeroImg.scaleY = 1.05f
            b.noirHeroImg.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(520)
                .start()
        }
        // NOIR (Zeigarnik, clean): bila item pernah ditonton, meta menambahkan
        // "68% ditonton" — progress kasat mata memicu dorongan menuntaskan.
        val pd = DataStoreHelper.getViewPos(item.id)
        b.noirHeroMeta.text = listOfNotNull(
            item.type?.name,
            item.apiName,
            pd?.takeIf { it.duration > 60_000L }?.let {
                "${((it.position.toFloat() / it.duration) * 100f).toInt().coerceIn(0, 100)}% ditonton"
            }
        ).joinToString(", ")
        b.noirHeroImg.loadImage(item.posterUrl) { size(512) }
        // NOIR: stripe "lanjutkan nonton" (VortX) bila ada progres tersimpan.
        if (pd != null && pd.duration > 0L) {
            b.noirHeroProgress.isVisible = true
            b.noirHeroProgress.progress =
                ((pd.position.toFloat() / pd.duration) * 100f).toInt().coerceIn(0, 100)
        } else {
            b.noirHeroProgress.isVisible = false
        }
        // NOIR: parallax + collapse hero saat scroll — hanya translate & opacity
        // (aturan panduan: jangan animasikan layout) supaya tetap 60fps.
        // NOIR inclusivity (prinsip award-winner): kalau sistem menyetel
        // "remove animations" (ANIMATOR_DURATION_SCALE=0), parallax & drift
        // aura dimatikan total.
        if (!noirScrollWired) {
            noirScrollWired = true
            b.homeMasterRecycler.addOnScrollListener(
                object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                    override fun onScrolled(
                        rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int,
                    ) {
                        if (noirReduceMotion) return
                        val hb = binding ?: return
                        val y = rv.computeVerticalScrollOffset().toFloat()
                        // NOIR FIX v2: gambar hero STATIS saat scroll — tidak
                        // ngangkat, tidak turun, tidak menimpa baris. Hanya
                        // judul & meta yang memudar (fokus ke konten).
                        hb.noirHeroImg.translationY = 0f
                        val fade = (1f - y / 420f).coerceIn(0f, 1f)
                        hb.noirHeroTitle.alpha = fade
                        hb.noirHeroMeta.alpha = fade * 0.7f
                        // NOIR (awwwards): hairline scroll-progress.
                        val range = rv.computeVerticalScrollRange() -
                            rv.computeVerticalScrollExtent()
                        hb.noirScrollProgress.scaleX =
                            if (range > 0) (y / range).coerceIn(0f, 1f) else 0f
                        // NOIR theme: aksen hairline — monochrome putih,
                        // colorful mengikuti warna hidup aura.
                        val spec = com.lagradost.cloudstream3.utils.NoirTheme.current(rv.context)
                        hb.noirScrollProgress.setBackgroundColor(
                            if (spec.accentFromAura) hb.noirAura.liveColor() else spec.accent
                        )
                    }

                    // NOIR perf: drift aura dijeda selama scroll (GPU fokus ke
                    // list = hilangkan rasa bottleneck), lanjut lagi saat idle.
                    override fun onScrollStateChanged(
                        rv: androidx.recyclerview.widget.RecyclerView, newState: Int,
                    ) {
                        binding?.noirAura?.setDriftEnabled(
                            newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE &&
                                    !noirReduceMotion
                        )
                    }
                }
            )
        }
        if (noirReduceMotion) b.noirAura.setDriftEnabled(false)
        // NOIR: aura background mengikuti warna poster hero — dinamis.
        b.noirAura.applyFromUrl(item.posterUrl)
        // NOIR interaction: tick haptic mikro pada aksi hero (prinsip award-
        // winner: haptik sebagai konfirmasi taktil, bukan getar panjang).
        b.noirHeroPlay.setOnClickListener { v ->
            com.lagradost.cloudstream3.utils.NoirHaptic.tap(v)
            activity?.loadSearchResult(item)
        }
        b.noirHeroRandom.setOnClickListener { v ->
            com.lagradost.cloudstream3.utils.NoirHaptic.tap(v)
            page.values.flatMap { it.list.list }
                .distinctBy { it.url }
                .randomOrNull()
                ?.let { r -> activity?.loadSearchResult(r) }
        }

        // NOIR variasi: daftarkan kandidat carousel + jadwalkan rotasi.
        noirHeroCandidates = page.values.asSequence()
            .flatMap { it.list.list }
            .filter { !it.posterUrl.isNullOrEmpty() && it.name.isNotBlank() }
            .distinctBy { it.url }
            .take(8)
            .toList()
        noirHeroIndex =
            noirHeroCandidates.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
        scheduleNoirHeroRotation(b)
    }

    // NOIR: latar beranda dinamis ala Apple — poster pertama dimuat kecil
    // (size 64) lalu di-upscale centerCrop = blur ambient halus tanpa RenderScript;
    // scrim monokrom di layout yang menjaga keterbacaan teks.
    private fun noirUpdateBackdrop(page: Map<String, HomeViewModel.ExpandableHomepageList>?) {
        // NOIR v3: latar foto dihapus — aura warna hasil ekstraksi cover
        // menjadi satu-satunya latar dinamis (bergerak, menyatu banner).
    }

    // NOIR: tampil/sembunyikan banner hero dengan animasi padding + fade.
    private fun noirApplyHeroVisibility(
        b: FragmentHomeBinding, hidden: Boolean, animate: Boolean
    ) {
        val density = resources.displayMetrics.density
        val targetPad = if (hidden) noirHiddenTopPad()
        else (400 * density).toInt()
        val rec = b.homeMasterRecycler
        // NOIR FIX: chevron kaca hero dipindah ke posisi nyaman diklik
        // (menungging tepi kartu hero, bukan di pojok atas layar).
        noirPositionHeroToggle(b, hidden)
        if (!animate || noirReduceMotion) {
            b.noirHero.isVisible = !hidden
            b.noirHero.alpha = if (hidden) 0f else 1f
            rec.setPadding(rec.paddingLeft, targetPad, rec.paddingRight, 0)
            return
        }
        if (hidden) {
            b.noirHero.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(280).withEndAction { b.noirHero.isVisible = false }.start()
        } else {
            b.noirHero.isVisible = true
            b.noirHero.alpha = 0f
            b.noirHero.scaleX = 0.96f; b.noirHero.scaleY = 0.96f
            b.noirHero.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start()
        }
        val from = rec.paddingTop
        android.animation.ValueAnimator.ofInt(from, targetPad).apply {
            duration = 300
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { v ->
                rec.setPadding(rec.paddingLeft, v.animatedValue as Int,
                    rec.paddingRight, 0)
            }
            start()
        }
    }


    // NOIR FIX: posisi chevron kaca hero — saat banner tampil ia menungging
    // tepi kanan-bawah kartu hero (mudah dijangkau ibu jari); saat banner
    // sembunyi ia turun ke bawah status bar (bukan di ujung pojok atas yang
    // tertutup ikon sistem).
    // NOIR: tinggi status bar perangkat (untuk mentokkan konten tepat di
    // bawahnya tanpa tebakan dp).
    private fun noirStatusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) try {
            resources.getDimensionPixelSize(id)
        } catch (_: Throwable) { 0 } else 0
    }

    // NOIR: padding atas recycler saat hero disembunyikan — pill search
    // mentok tepat di bawah status bar (tanpa jarak mengambang).
    private fun noirHiddenTopPad(): Int =
        noirStatusBarHeight() + (6 * resources.displayMetrics.density).toInt()

    private fun noirPositionHeroToggle(b: FragmentHomeBinding, hidden: Boolean) {
        val density = resources.displayMetrics.density
        val statusH = noirStatusBarHeight()
        val lp = b.noirHeroToggle.layoutParams as? android.widget.FrameLayout.LayoutParams
            ?: return
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        if (hidden) {
            // Mode ringkas: chevron menempati kolom avatar di baris search,
            // satu garis dengan pill yang mentok bawah status bar.
            lp.marginEnd = (1 * density).toInt()
            lp.topMargin = statusH + (7 * density).toInt()
        } else {
            // Mode hero: chevron duduk penuh di dalam sudut kanan-bawah
            // banner (tidak menimpa avatar baris search di bawahnya).
            lp.marginEnd = (20 * density).toInt()
            lp.topMargin = ((400 - 56) * density).toInt()
        }
        b.noirHeroToggle.layoutParams = lp
        noirApplyCompactHeader(b, hidden)
    }

    // NOIR: mode ringkas (hero sembunyi) — avatar kolom kanan diganti chevron,
    // pill search melebar mentok atas; mode hero mengembalikan avatar.
    private fun noirApplyCompactHeader(b: FragmentHomeBinding, hidden: Boolean) {
        val density = resources.displayMetrics.density
        val avatar = b.root.findViewById<android.view.View>(R.id.home_head_profile_padding)
        val pill = b.root.findViewById<android.view.View>(R.id.home_search)
        if (avatar == null || pill == null) {
            // header recycler belum ter-inflate; coba lagi setelah layout.
            b.homeMasterRecycler.post { noirApplyCompactHeader(b, hidden) }
            return
        }
        avatar.isVisible = !hidden
        (pill.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { m ->
            val end = if (hidden) (56 * density).toInt() else (8 * density).toInt()
            if (m.marginEnd != end) {
                m.marginEnd = end
                pill.layoutParams = m
            }
        }
    }

    companion object {
        // Used for configuration changed events to fix any popups that are not attached to a fragment
        val configEvent = EmptyEvent()
        var currentSpan = 1

        private val errorProfilePics = listOf(
            R.drawable.monke_benene,
            R.drawable.monke_burrito,
            R.drawable.monke_coco,
            R.drawable.monke_cookie,
            R.drawable.monke_flusdered,
            R.drawable.monke_funny,
            R.drawable.monke_like,
            R.drawable.monke_party,
            R.drawable.monke_sob,
            R.drawable.monke_drink,
        )

        val errorProfilePic = errorProfilePics.random()

        //fun Activity.loadHomepageList(
        //    item: HomePageList,
        //    deleteCallback: (() -> Unit)? = null,
        //) {
        //    loadHomepageList(
        //        expand = HomeViewModel.ExpandableHomepageList(item, 1, false),
        //        deleteCallback = deleteCallback,
        //        expandCallback = null
        //    )
        //}

        // returns a BottomSheetDialog that will be hidden with OwnHidden upon hide, and must be saved to be able call ownShow in onCreateView

        fun Activity.loadHomepageList(
            expand: HomeViewModel.ExpandableHomepageList,
            deleteCallback: (() -> Unit)? = null,
            expandCallback: (suspend (String) -> HomeViewModel.ExpandableHomepageList?)? = null,
            dismissCallback: (() -> Unit),
        ): BottomSheetDialog {
            val context = this
            val bottomSheetDialogBuilder = BottomSheetDialog(context)
            val binding: HomeEpisodesExpandedBinding = HomeEpisodesExpandedBinding.inflate(
                bottomSheetDialogBuilder.layoutInflater,
                null,
                false
            )
            bottomSheetDialogBuilder.setContentView(binding.root)
            //val title = bottomSheetDialogBuilder.findViewById<TextView>(R.id.home_expanded_text)!!

            //title.findViewTreeLifecycleOwner().lifecycle.addObserver()

            val item = expand.list
            binding.homeExpandedText.text = item.name
            // val recycle =
            //    bottomSheetDialogBuilder.findViewById<AutofitRecyclerView>(R.id.home_expanded_recycler)!!
            //val titleHolder =
            //    bottomSheetDialogBuilder.findViewById<FrameLayout>(R.id.home_expanded_drag_down)!!

            // main {
            //(bottomSheetDialogBuilder.ownerActivity as androidx.fragment.app.FragmentActivity?)?.supportFragmentManager?.fragments?.lastOrNull()?.viewLifecycleOwner?.apply {
            //    println("GOT LIFE: lifecycle $this")
            //    this.lifecycle.addObserver(object : DefaultLifecycleObserver {
            //        override fun onResume(owner: LifecycleOwner) {
            //            super.onResume(owner)
            //            println("onResume!!!!")
            //            bottomSheetDialogBuilder?.ownShow()
            //        }

            //        override fun onStop(owner: LifecycleOwner) {
            //            super.onStop(owner)
            //            bottomSheetDialogBuilder?.ownHide()
            //        }
            //    })
            //}
            // }
            //val delete = bottomSheetDialogBuilder.home_expanded_delete
            binding.homeExpandedDelete.isGone = deleteCallback == null
            if (deleteCallback != null) {
                binding.homeExpandedDelete.setOnClickListener {
                    try {
                        val builder: AlertDialog.Builder = AlertDialog.Builder(context)
                        val dialogClickListener =
                            DialogInterface.OnClickListener { _, which ->
                                when (which) {
                                    DialogInterface.BUTTON_POSITIVE -> {
                                        deleteCallback.invoke()
                                        bottomSheetDialogBuilder.dismissSafe(this)
                                    }

                                    DialogInterface.BUTTON_NEGATIVE -> {}
                                }
                            }

                        builder.setTitle(R.string.clear_history)
                            .setMessage(
                                context.getString(R.string.delete_message).format(
                                    item.name
                                )
                            )
                            .setPositiveButton(R.string.delete, dialogClickListener)
                            .setNegativeButton(R.string.cancel, dialogClickListener)
                            .show().setDefaultFocus()
                    } catch (e: Exception) {
                        logError(e)
                        // ye you somehow fucked up formatting did you?
                    }
                }
            }
            binding.homeExpandedDragDown.setOnClickListener {
                bottomSheetDialogBuilder.dismissSafe(this)
            }


            // Span settings
            binding.homeExpandedRecycler.spanCount = context.getSpanCount(item.isHorizontalImages)
            binding.homeExpandedRecycler.setRecycledViewPool(SearchAdapter.sharedPool)
            // NOIR: tanpa animasi change = refresh home bebas stutter.
            (binding.homeExpandedRecycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            binding.homeExpandedRecycler.adapter =
                SearchAdapter(binding.homeExpandedRecycler,item.isHorizontalImages) { callback ->
                    handleSearchClickCallback(callback)
                    if (callback.action == SEARCH_ACTION_LOAD || callback.action == SEARCH_ACTION_PLAY_FILE) {
                        bottomSheetDialogBuilder.ownHide() // we hide here because we want to resume it later
                        //bottomSheetDialogBuilder.dismissSafe(this)
                    }
                }.apply {
                    submitList(item.list)
                    hasNext = expand.hasNext
                }

            binding.homeExpandedRecycler.addOnScrollListener(object :
                RecyclerView.OnScrollListener() {
                var expandCount = 0
                val name = expand.list.name

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    val adapter = recyclerView.adapter
                    if (adapter !is SearchAdapter) return

                    val count = adapter.itemCount
                    val currentHasNext = adapter.hasNext
                    //!recyclerView.canScrollVertically(1)
                    if (!recyclerView.isRecyclerScrollable() && currentHasNext && expandCount != count) {
                        expandCount = count
                        ioSafe {
                            expandCallback?.invoke(name)?.let { newExpand ->
                                (recyclerView.adapter as? SearchAdapter?)?.apply {
                                    hasNext = newExpand.hasNext
                                    submitList(newExpand.list.list)
                                }
                            }
                        }
                    }
                }
            })

            val spanListener = Runnable {
                binding.homeExpandedRecycler.spanCount = context.getSpanCount(item.isHorizontalImages)
                // We want to rebind everything to update the UI, however we also want to avoid
                // any animations ect, this is the easiest way to do this, and the most correct
                @SuppressLint("NotifyDataSetChanged")
                binding.homeExpandedRecycler.adapter?.notifyDataSetChanged()
            }

            configEvent += spanListener

            bottomSheetDialogBuilder.setOnDismissListener {
                dismissCallback.invoke()
                configEvent -= spanListener
            }

            //(recycle.adapter as SearchAdapter).notifyDataSetChanged()

            bottomSheetDialogBuilder.show()
            return bottomSheetDialogBuilder
        }

        private fun getPairList(
            anime: Chip?,
            cartoons: Chip?,
            tvs: Chip?,
            docs: Chip?,
            movies: Chip?,
            asian: Chip?,
            livestream: Chip?,
            torrent: Chip?,
            nsfw: Chip?,
            others: Chip?,
        ): List<Pair<Chip?, List<TvType>>> {
            // This list should be same order as home screen to aid navigation
            return listOf(
                Pair(movies, listOf(TvType.Movie)),
                Pair(tvs, listOf(TvType.TvSeries)),
                Pair(anime, listOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)),
                Pair(asian, listOf(TvType.AsianDrama)),
                Pair(cartoons, listOf(TvType.Cartoon)),
                Pair(docs, listOf(TvType.Documentary)),
                Pair(livestream, listOf(TvType.Live)),
                Pair(torrent, listOf(TvType.Torrent)),
                Pair(nsfw, listOf(TvType.NSFW)),
                Pair(others, listOf(TvType.Others)),
            )
        }

        private fun getPairList(header: TvtypesChipsBinding) = getPairList(
            header.homeSelectAnime,
            header.homeSelectCartoons,
            header.homeSelectTvSeries,
            header.homeSelectDocumentaries,
            header.homeSelectMovies,
            header.homeSelectAsian,
            header.homeSelectLivestreams,
            header.homeSelectTorrents,
            header.homeSelectNsfw,
            header.homeSelectOthers
        )

        fun validateChips(header: TvtypesChipsBinding?, validTypes: List<TvType>) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                val isValid = validTypes.any { types.contains(it) }
                button?.isVisible = isValid
            }
        }

        fun updateChips(header: TvtypesChipsBinding?, selectedTypes: List<TvType>) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                button?.isChecked =
                    button.isVisible && selectedTypes.any { types.contains(it) }
            }
        }

        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit
        ) {
            bindChips(header, selectedTypes, validTypes, callback, null, null)
        }

        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit,
            nextFocusDown: Int?,
            nextFocusUp: Int?
        ) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                val isValid = validTypes.any { types.contains(it) }
                button?.isVisible = isValid
                button?.isChecked = isValid && selectedTypes.any { types.contains(it) }
                button?.isFocusable = true
                if (isLayout(TV)) {
                    button?.isFocusableInTouchMode = true
                }

                if (nextFocusDown != null)
                    button?.nextFocusDownId = nextFocusDown

                if (nextFocusUp != null)
                    button?.nextFocusUpId = nextFocusUp

                button?.setOnCheckedChangeListener { _, _ ->
                    val list = ArrayList<TvType>()
                    for ((sbutton, vvalidTypes) in pairList) {
                        if (sbutton?.isChecked == true)
                            list.addAll(vvalidTypes)
                    }
                    callback(list)
                }
            }
        }

        fun Context.selectHomepage(selectedApiName: String?, callback: (String) -> Unit) {
            val validAPIs = filterProviderByPreferredMedia().toMutableList()

            validAPIs.add(0, randomApi)
            validAPIs.add(0, noneApi)
            //val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            //builder.setView(R.layout.home_select_mainpage)
            val builder =
                BottomSheetDialog(this)

            builder.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            val binding: HomeSelectMainpageBinding = HomeSelectMainpageBinding.inflate(
                builder.layoutInflater,
                null,
                false
            )

            builder.setContentView(binding.root)
            builder.show()
            builder.let { dialog ->
                val isMultiLang = getApiProviderLangSettings().let { set ->
                    set.size > 1 || set.contains(AllLanguagesName)
                }
                //dialog.window?.setGravity(Gravity.BOTTOM)

                var currentApiName = selectedApiName

                var currentValidApis: MutableList<MainAPI> = mutableListOf()
                val preSelectedTypes = DataStoreHelper.homePreference.toMutableList()

                binding.cancelBtt.setOnClickListener {
                    dialog.dismissSafe()
                }

                binding.applyBtt.setOnClickListener {
                    if (currentApiName != selectedApiName) {
                        currentApiName?.let(callback)
                    }
                    dialog.dismissSafe()
                }

                var pinnedphashset = DataStoreHelper.pinnedProviders.toHashSet()

                val listView = dialog.findViewById<ListView>(R.id.listview1)

                val arrayAdapter = object : ArrayAdapter<CharSequence>(
                    this, R.layout.sort_bottom_single_provider_choice,
                    mutableListOf()
                ) {
                    override fun getView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup
                    ): View {
                        val view = convertView ?: LayoutInflater.from(context)
                            .inflate(R.layout.sort_bottom_single_provider_choice, parent, false)
                        val titleText = view.findViewById<TextView>(R.id.text1)
                        val pinIcon = view.findViewById<ImageView>(R.id.pinicon)
                        val settingsIcon = view.findViewById<ImageView>(R.id.action_settings)

                        val name = getItem(position)
                        titleText?.text = name
                        val providerApi = currentValidApis[position]
                        val isPinned =
                            pinnedphashset.contains(providerApi.name)
                        pinIcon.visibility = if (isPinned) View.VISIBLE else View.GONE

                        val pluginInstance = providerApi.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                        val isDownloadedPluginWithSettings = pluginInstance?.openSettings != null && !isLayout(TV)

                        settingsIcon.visibility = if (isDownloadedPluginWithSettings) View.VISIBLE else View.GONE
                        if (isDownloadedPluginWithSettings) {
                            settingsIcon.setOnClickListener {
                                try {
                                    val activityContext = it.context.getActivity() ?: it.context
                                    pluginInstance.openSettings?.invoke(activityContext)
                                } catch (e: Throwable) {
                                    logError(e)
                                }
                            }
                        }

                        return view
                    }
                }
                listView?.adapter = arrayAdapter
                listView?.choiceMode = AbsListView.CHOICE_MODE_SINGLE

                listView?.setOnItemClickListener { _, _, i, _ ->
                    if (currentValidApis.isNotEmpty()) {
                        currentApiName = currentValidApis[i].name
                        //to switch to apply simply remove this
                        currentApiName.let(callback)
                        dialog.dismissSafe()
                    }
                }

                fun updateList() {
                    DataStoreHelper.homePreference = preSelectedTypes
                    val pinnedp = DataStoreHelper.pinnedProviders.toList()
                    pinnedphashset = pinnedp.toHashSet()
                    arrayAdapter.clear()
                    val sortedApis = validAPIs
                        .filter {
                            val isPinned = pinnedphashset.contains(it.name)

                            // Hide pinned NSFW when NSFW not selected. NSFW is distracting when not chosen.
                            if (isPinned && !preSelectedTypes.contains(TvType.NSFW)) {
                                if (it.supportedTypes.all { type -> type == TvType.NSFW }) return@filter false
                            }

                            it.hasMainPage && (isPinned || it.supportedTypes.any(
                                preSelectedTypes::contains
                            ))
                        }
                        .sortedBy { it.name.lowercase() }

                    val sortedApiMap = LinkedHashMap<String, MainAPI>().apply {
                        sortedApis.forEach { put(it.name, it) }
                    }

                    val pinnedApis = pinnedp.asReversed().mapNotNull { name ->
                        sortedApiMap[name]
                    }

                    val remainingApis = sortedApis.filterNot { pinnedphashset.contains(it.name) }

                    currentValidApis = mutableListOf<MainAPI>().apply {
                        addAll(validAPIs.take(2))
                        addAll(pinnedApis)
                        addAll(remainingApis)
                    }

                    val names: List<CharSequence> =
                        currentValidApis.map { api ->
                            if (isMultiLang)
                                com.lagradost.cloudstream3.utils.NoirFlags.span(
                                    this, api.lang, api.name
                                )
                            else api.name
                        }
                    val index = currentValidApis.map { it.name }.indexOf(currentApiName)
                    listView?.setItemChecked(index, true)
                    arrayAdapter.addAll(names)
                    arrayAdapter.notifyDataSetChanged()
                }
                // pin provider on hold
                listView?.setOnItemLongClickListener { _, _, i, _ ->
                    if (currentValidApis.isNotEmpty() && i > 1) {
                        val pinnedp = DataStoreHelper.pinnedProviders.toMutableList()
                        val thisapi = currentValidApis[i].name
                        if (pinnedp.contains(thisapi)) {
                            pinnedp.remove(thisapi)
                        } else {
                            pinnedp.add(thisapi)
                        }
                        DataStoreHelper.pinnedProviders = pinnedp.toTypedArray()
                        updateList()
                    }
                    true
                }

                bindChips(
                    binding.tvtypesChipsScroll.tvtypesChips,
                    preSelectedTypes,
                    validAPIs.flatMap { it.supportedTypes }.distinct()
                ) { list ->
                    preSelectedTypes.clear()
                    preSelectedTypes.addAll(list)
                    updateList()
                }
                updateList()
            }
        }
    }

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val accountViewModel: AccountViewModel by activityViewModels()

    fun addMovies(cards: List<SearchResponse>) {
        val ctx = context ?: run {
            Log.e(TAG, "Context is null, aborting addMovies")
            return
        }

        try {
            val existingId = TvChannelUtils.getChannelId(ctx, getString(R.string.app_name))
            if (existingId != null) {
                Log.d(TAG, "Channel ID: $existingId")

                val programCards = cards

                TvChannelUtils.addPrograms(
                    context = ctx,
                    channelId = existingId,
                    items = programCards
                )
            } else {
                Log.d(TAG, "Channel does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding movies: $e")
        }
    }

    private fun deleteAll() {
        val ctx = context ?: run {
            Log.e(TAG, "Context is null, aborting deleteAll")
            return
        }

        try {
            val existingId = TvChannelUtils.getChannelId(ctx, getString(R.string.app_name))
            if (existingId != null) {
                Log.d(TAG, "Channel ID: $existingId")
                TvChannelUtils.deleteStoredPrograms(ctx)
            } else {
                Log.d(TAG, "Channel does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting programs: ${e.message}")
        }
    }

    override fun pickLayout(): Int? =
        if (isLayout(PHONE)) R.layout.fragment_home else R.layout.fragment_home_tv

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        bottomSheetDialog?.ownShow()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        (activity as? ComponentActivity)?.detachBackPressedCallback("HomeFragment_BackPress")
        bottomSheetDialog?.ownHide()
        super.onDestroyView()
    }

    private val apiChangeClickListener = View.OnClickListener { view ->
        view.context.selectHomepage(currentApiName) { api ->
            homeViewModel.loadAndCancel(api, forceReload = true, fromUI = true)
        }
        /*val validAPIs = view.context?.filterProviderByPreferredMedia()?.toMutableList() ?: mutableListOf()

        validAPIs.add(0, randomApi)
        validAPIs.add(0, noneApi)
        view.popupMenuNoIconsAndNoStringRes(validAPIs.mapIndexed { index, api -> Pair(index, api.name) }) {
            homeViewModel.loadAndCancel(validAPIs[itemId].name)
        }*/
    }

    private var currentApiName: String? = null
    private var toggleRandomButton = false

    private var bottomSheetDialog: BottomSheetDialog? = null
    private var homeMasterAdapter: HomeParentItemAdapterPreview? = null

    var lastSavedHomepage: String? = null

    fun saveHomepageToTV(page: Map<String, HomeViewModel.ExpandableHomepageList>) {
        // No need to update for phone
        if (isLayout(PHONE)) {
            return
        }
        val (name, data) = page.entries.firstOrNull() ?: return
        // Modifying homepage is an expensive operation, and therefore we avoid it at all cost
        if (name == lastSavedHomepage) {
            return
        }
        Log.i(TAG, "Adding programs $name to TV")
        lastSavedHomepage = name
        ioSafe {
            // empty the channel
            deleteAll()
            // insert the program from first array
            addMovies(data.list.list)
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padTop = false,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )

        // Fix grid
        configEvent.invoke()
    }

    @SuppressLint("SetTextI18n")
    override fun onBindingCreated(binding: FragmentHomeBinding) {
        com.lagradost.cloudstream3.utils.NoirTuning.refresh(requireContext())
        // NOIR (uibeats/a11y): stagger reveal hanya bila motion diizinkan.
        HomeChildItemAdapter.noirStaggerEnabled = !noirReduceMotion
        // NOIR theme: search pill beranda + hairline ikut resep tema.
        binding.root.findViewById<android.view.View>(R.id.home_search)?.background =
            com.lagradost.cloudstream3.utils.NoirTheme.liquidGlass(requireContext(), 26f)
        // NOIR FIX: aura harus langsung bercahaya sejak layar lahir (fallback
        // aksen tema); begitu poster terunduh, applyFromUrl menimpanya dengan
        // warna asli film. Tanpa ini, download gagal = aura hitam mati.
        com.lagradost.cloudstream3.utils.NoirTheme.current(requireContext()).let { sp ->
            binding.noirAura.setColors(sp.accent, 0xFF2B2B33.toInt())
        }

        // NOIR: toggle banner hero — sembunyi/tampil dgn animasi, tersimpan.
        val prefsH = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(requireContext())
        val heroHidden = prefsH.getBoolean("noir_hero_hidden", false)
        binding.noirHeroToggle.apply {
            background = com.lagradost.cloudstream3.utils.NoirTheme
                .liquidGlass(requireContext(), 20f)
            rotation = if (heroHidden) 180f else 0f
            setOnClickListener {
                val nowHidden = !prefsH.getBoolean("noir_hero_hidden", false)
                prefsH.edit().putBoolean("noir_hero_hidden", nowHidden).apply()
                animate().rotation(if (nowHidden) 180f else 0f).setDuration(300).start()
                noirApplyHeroVisibility(binding, nowHidden, true)
                com.lagradost.cloudstream3.utils.NoirHaptic.tap(it)
            }
        }
        noirApplyHeroVisibility(binding, heroHidden, false)
        context?.let { HomeChildItemAdapter.updatePosterSize(it) }
        (activity as? ComponentActivity)?.attachBackPressedCallback("HomeFragment_BackPress") {
            handleTvBackPress(this)
        }
        binding.apply {
            //homeChangeApiLoading.setOnClickListener(apiChangeClickListener)
            //homeChangeApiLoading.setOnClickListener(apiChangeClickListener)
            homeApiFab.setOnClickListener(apiChangeClickListener)
            homeApiFab.setOnLongClickListener {
                if (currentApiName == noneApi.name) return@setOnLongClickListener false
                homeViewModel.loadAndCancel(currentApiName, forceReload = true, fromUI = true)
                showToast(R.string.action_reload, Toast.LENGTH_SHORT)
                true
            }
            homeChangeApi.setOnClickListener(apiChangeClickListener)
            homeSwitchAccount.setOnClickListener {
                activity?.showAccountSelectLinear()
            }

            homeMasterAdapter = HomeParentItemAdapterPreview(
                homeViewModel, accountViewModel
            )
            homeMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            homeMasterRecycler.adapter = homeMasterAdapter
            // NOIR smooth: tanpa animasi change = refresh baris home bebas flicker.
            (homeMasterRecycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations =
                false

            homeApiFab.isVisible = isLayout(PHONE)

            homePreviewReloadProvider.setOnClickListener {
                homeViewModel.loadAndCancel(
                    homeViewModel.apiName.value ?: noneApi.name,
                    forceReload = true,
                    fromUI = true
                )
                showToast(R.string.action_reload, Toast.LENGTH_SHORT)
            }

            homePreviewSearchButton.setOnClickListener { _ ->
                // Open blank screen.
                homeViewModel.queryTextSubmit("")
            }

            homeMasterRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (isLayout(PHONE)) {
                        // Fab is only relevant to Phone
                        if (dy > 0) { //check for scroll down
                            homeApiFab.shrink() // hide
                            homeRandom.shrink()
                            noirSetNavHidden(true) // NOIR: tab bar menyelam
                        } else if (dy < -5) {
                            if (isLayout(PHONE)) {
                                homeApiFab.extend() // show
                                homeRandom.extend()
                                noirSetNavHidden(false) // NOIR: tab bar balik
                            }
                        }
                        // NOIR: di puncak halaman tab bar selalu tampil
                        if (recyclerView.computeVerticalScrollOffset() == 0) {
                            noirSetNavHidden(false)
                        }
                    } else {
                        // Header scrolling is only relevant to TV/Emulator

                        val view = recyclerView.findViewHolderForAdapterPosition(0)?.itemView
                        val scrollParent = binding.homeApiHolder

                        if (view == null) {
                            // The first view is not visible, so we can assume we have scrolled past it
                            scrollParent.isVisible = false
                        } else {
                            // A bit weird, but this is a major limitation we are working around here
                            // 1. We cant have a real parent to the recyclerview as android cant layout that without lagging
                            // 2. We cant put the view in the recyclerview, as it should always be shown
                            // 3. We cant mirror the view in the recyclerview as then it causes focus issues when swaping out the mirror view
                            //
                            // This means that if we want to have a parent view to the recyclerview we are out of luck
                            // Instead this uses getLocationInWindow to calculate how much the view should be scrolled
                            // as recyclerView has no scrollY (always 0)
                            //
                            // Then it manually "scrolls" it to the correct position
                            //
                            // Hopefully getLocationInWindow acts correctly on all devices
                            val rect = IntArray(2)
                            view.getLocationInWindow(rect)
                            scrollParent.isVisible = true
                            scrollParent.translationY = rect[1].toFloat() - 60.toPx
                        }
                    }
                    super.onScrolled(recyclerView, dx, dy)
                }
            })

        }

        //Load value for toggling Random button. Hide at startup
        context?.let {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
            toggleRandomButton =
                settingsManager.getBoolean(
                    getString(R.string.random_button_key),
                    false
                )
            binding.homeRandom.visibility = View.GONE
            binding.homeRandomButtonTv.visibility = View.GONE
        }

        observe(homeViewModel.apiName) { apiName ->
            currentApiName = apiName
            binding.apply {
                homeApiFab.text = apiName
                homeChangeApi.text = apiName
                homePreviewReloadProvider.isGone = (apiName == noneApi.name)
                homePreviewSearchButton.isGone = (apiName == noneApi.name)
            }
        }

        observe(homeViewModel.page) { data ->
            binding.apply {
                when (data) {
                    is Resource.Success -> {
                        val d = data.value
                        noirUpdateBackdrop(d)
                        noirUpdateHero(d)
                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.submitList(d.values.map {
                            it.copy(
                                list = it.list.copy(list = it.list.list.toMutableList())
                            )
                        })

                        saveHomepageToTV(d)

                        homeLoading.isVisible = false
                        homeLoadingError.isVisible = false
                        homeMasterRecycler.isVisible = true
                        homeLoadingShimmer.stopShimmer()
                        //home_loaded?.isVisible = true
                        if (toggleRandomButton) {
                            val distinct = d.values
                                .flatMap { it.list.list }
                                .distinctBy { it.url }
                            val hasItems = distinct.isNotEmpty()
                            val isPhone = isLayout(PHONE)
                            val randomClickListener = View.OnClickListener {
                                distinct.randomOrNull()?.let { activity.loadSearchResult(it) }
                            }

                            homeRandom.isVisible = isPhone && hasItems
                            homeRandom.setOnClickListener(randomClickListener)
                            homeRandomButtonTv.isVisible = !isPhone && hasItems
                            homeRandomButtonTv.setOnClickListener(randomClickListener)
                        } else {
                            homeRandom.isGone = true
                            homeRandomButtonTv.isGone = true
                        }
                    }

                    is Resource.Failure -> {
                        homeLoadingShimmer.stopShimmer()
                        homeReloadConnectionerror.setOnClickListener(apiChangeClickListener)
                        homeReloadConnectionOpenInBrowser.setOnClickListener { view ->
                            val validAPIs = apis//.filter { api -> api.hasMainPage }

                            view.popupMenuNoIconsAndNoStringRes(validAPIs.mapIndexed { index, api ->
                                Pair(
                                    index,
                                    api.name
                                )
                            }) {
                                try {
                                    val i = Intent(Intent.ACTION_VIEW)
                                    i.data = validAPIs[itemId].mainUrl.toUri()
                                    startActivity(i)
                                } catch (e: Exception) {
                                    logError(e)
                                }
                            }
                        }

                        homeLoading.isVisible = false
                        homeLoadingError.isVisible = true
                        homeMasterRecycler.isInvisible = true

                        // Based on https://github.com/recloudstream/cloudstream/pull/1438
                        val hasNoNetworkConnection = context?.isNetworkAvailable() == false
                        val isNetworkError = data.isNetworkError

                        // Show the downloads button if we have any sort of network shenanigans
                        homeReloadConnectionGoToDownloads.isVisible =
                            hasNoNetworkConnection || isNetworkError

                        // Only hide the open in browser button if we know this is not network shenanigans related to cs3
                        homeReloadConnectionOpenInBrowser.isGone = hasNoNetworkConnection

                        resultErrorText.text = if (hasNoNetworkConnection) {
                            getString(R.string.no_internet_connection)
                        } else {
                            data.errorString
                        }

                        homeReloadConnectionGoToDownloads.setOnClickListener {
                            activity.navigate(R.id.navigation_downloads)
                        }

                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                    }

                    is Resource.Loading -> {
                        homeLoadingShimmer.startShimmer()
                        homeLoading.isVisible = true
                        homeLoadingError.isVisible = false
                        homeMasterRecycler.isInvisible = true
                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                        //home_loaded?.isVisible = false
                    }
                }
            }
        }

        observeNullable(homeViewModel.popup) { item ->
            if (item == null) {
                bottomSheetDialog?.dismissSafe()
                bottomSheetDialog = null
                return@observeNullable
            }

            // don't recreate
            if (bottomSheetDialog != null) {
                return@observeNullable
            }

            val (items, delete) = item

            bottomSheetDialog = activity?.loadHomepageList(items, expandCallback = {
                homeViewModel.expandAndReturn(it)
            }, dismissCallback = {
                homeViewModel.popup(null)
                bottomSheetDialog = null
            }, deleteCallback = delete)
        }

        homeViewModel.reloadStored()
        homeViewModel.loadAndCancel(DataStoreHelper.currentHomePage, false)
        //loadHomePage(false)

        // nice profile pic on homepage
        //home_profile_picture_holder?.isVisible = false
        // just in case

        //TODO READD THIS
        /*for (syncApi in OAuth2Apis) {
            val login = SyncAPI2.loginInfo()
            val pic = login?.profilePicture
            if (home_profile_picture?.setImage(
                    pic,
                    errorImageDrawable = errorProfilePic
                ) == true
            ) {
                home_profile_picture_holder?.isVisible = true
                break
            }
        }*/
    }

    private fun handleTvBackPress(helper: BackPressedCallbackHelper.CallbackHelper) {
        // Only apply custom behavior on TV interface
        if (!isLayout(TV)) {
            helper.runDefault()
            return
        }
        val currentFocus = activity?.currentFocus ?: run {
            helper.runDefault()
            return
        }
        // isInsideRecycle is true when focus is inside home_master_recycler
        var parent = currentFocus.parent
        var isInsideRecycler = false
        while (parent != null) {
            if (parent is View && parent.id == R.id.home_master_recycler) {
                isInsideRecycler = true
                break
            }
            parent = parent.parent
        }
        when {
            // Case 1: Focus is within plugin content -> Move to plugin selector
            isInsideRecycler -> {
                binding?.homeMasterRecycler?.scrollToPosition(0)
                // Defer focus request until after scroll ends
                binding?.homeChangeApi?.post {
                    binding?.homeChangeApi?.requestFocus()
                }
            }
            // Case 2: Focus is on plugin selector or nearby buttons -> Move to home navigation
            currentFocus.id == R.id.home_change_api ||
            currentFocus.id == R.id.home_preview_reload_provider ||
            currentFocus.id == R.id.home_preview_search_button -> {
                activity?.findViewById<View>(R.id.navigation_home)?.requestFocus()
            }
            // Case 3: Any other location -> Use default back behavior
            else -> helper.runDefault()
        }
    }
}
