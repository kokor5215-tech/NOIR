package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.HomeResultRankBinding
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.NoirHaptic

/**
 * NOIR variasi: baris "Top 10" dengan angka raksasa + poster (ala Netflix).
 * Subclass HomeChildItemAdapter supaya ikut pool & diffing yang sama; bind
 * manual karena layout-nya berbeda dari kartu poster biasa.
 */
class NoirRankAdapter(
    id: Int,
    clickCallback: (SearchClickCallback) -> Unit,
) : HomeChildItemAdapter(id = id, clickCallback = clickCallback) {

    // NOIR FIX KRITIS: tanpa ini, holder layout rank (tanpa id imageView)
    // masuk bucket recycle yang SAMA dengan kartu poster biasa di shared
    // pool — saat holder rank dipinjam grid biasa, findViewById(imageView)
    // null → NPE → crash → app restart berulang.
    override fun customContentViewType(item: SearchResponse): Int = 1

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Boolean> {
        val binding = HomeResultRankBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HomeScrollViewHolderState(binding)
    }

    override fun onBindContent(
        holder: ViewHolderState<Boolean>,
        item: SearchResponse,
        position: Int,
    ) {
        val binding = holder.view as? HomeResultRankBinding ?: return
        binding.rankNumber.text = (position + 1).toString()
        // NOIR perf: decode kecil — kartu rank cuma 104dp.
        binding.rankPoster.loadImage(item.posterUrl, item.posterHeaders) {
            size(300, 450)
        }
        binding.root.setOnClickListener { v ->
            NoirHaptic.tap(v)
            clickCallback(
                SearchClickCallback(SEARCH_ACTION_LOAD, v, position, item)
            )
        }
    }
}
