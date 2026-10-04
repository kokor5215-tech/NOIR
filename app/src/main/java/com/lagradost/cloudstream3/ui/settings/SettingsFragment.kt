package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.utils.DeviceIdentity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.MainSettingsBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthRepo
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.errorProfilePic
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.txt
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SettingsFragment : BaseFragment<MainSettingsBinding>(
    BaseFragment.BindingCreator.Inflate(MainSettingsBinding::inflate)
) {
    companion object {
        fun PreferenceFragmentCompat?.getPref(id: Int): Preference? {
            if (this == null) return null
            return try {
                findPreference(getString(id))
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        fun PreferenceFragmentCompat?.hidePrefs(ids: List<Int>, layoutFlags: Int) {
            if (this == null) return
            try {
                ids.forEach {
                    getPref(it)?.isVisible = !isLayout(layoutFlags)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        fun Preference?.hideOn(layoutFlags: Int): Preference? {
            if (this == null) return null
            this.isVisible = !isLayout(layoutFlags)
            return if(this.isVisible) this else null
        }

        fun PreferenceFragmentCompat.setPaddingBottom() {
            if (isLayout(TV or EMULATOR)) {
                listView?.setPadding(0, 0, 0, 100.toPx)
            }
        }

        fun PreferenceFragmentCompat.setToolBarScrollFlags() {
            if (isLayout(TV or EMULATOR)) {
                val settingsAppbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar)
                settingsAppbar?.updateLayoutParams<AppBarLayout.LayoutParams> {
                    scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                }
            }
        }

        fun Fragment?.setToolBarScrollFlags() {
            if (isLayout(TV or EMULATOR)) {
                val settingsAppbar = this?.view?.findViewById<MaterialToolbar>(R.id.settings_toolbar)
                settingsAppbar?.updateLayoutParams<AppBarLayout.LayoutParams> {
                    scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                }
            }
        }

        fun Fragment?.setUpToolbar(title: String) {
            if (this == null) return
            val settingsToolbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar) ?: return
            settingsToolbar.apply {
                setTitle(title)
                if (isLayout(PHONE or EMULATOR)) {
                    setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
                    setNavigationOnClickListener {
                        activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                }
            }
        }

        fun Fragment?.setUpToolbar(@StringRes title: Int) {
            if (this == null) return
            val settingsToolbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar) ?: return
            settingsToolbar.apply {
                setTitle(title)
                if (isLayout(PHONE or EMULATOR)) {
                    setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
                    children.firstOrNull { it is ImageView }?.tag = getString(R.string.tv_no_focus_tag)
                    setNavigationOnClickListener {
                        safe { activity?.onBackPressedDispatcher?.onBackPressed() }
                    }
                }
            }
        }

        fun Fragment.setSystemBarsPadding() {
            view?.let {
                fixSystemBarsPadding(
                    it,
                    padLeft = isLayout(TV or EMULATOR),
                    padBottom = isLandscape()
                )
            }
        }

        fun getFolderSize(dir: File): Long {
            var size: Long = 0
            dir.listFiles()?.let {
                for (file in it) {
                    size += if (file.isFile) {
                        file.length()
                    } else getFolderSize(file)
                }
            }
            return size
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: MainSettingsBinding) {
        fun navigate(id: Int) {
            activity?.navigate(id, Bundle())
        }

        fun hasProfilePictureFromAccountManagers(accountManagers: Array<AuthRepo>): Boolean {
            for (syncApi in accountManagers) {
                val login = syncApi.authUser()
                val pic = login?.profilePicture ?: continue
                binding.settingsProfilePic.let { imageView ->
                    imageView.loadImage(pic) {
                        error { getImageFromDrawable(context ?: return@error null, errorProfilePic) }
                    }
                }
                binding.settingsProfileText.text = login.name
                return true
            }
            return false
        }

        if (!hasProfilePictureFromAccountManagers(AccountManager.allApis)) {
            val activity = activity ?: return
            val currentAccount = try {
                DataStoreHelper.accounts.firstOrNull {
                    it.keyIndex == DataStoreHelper.selectedKeyIndex
                } ?: activity.let { DataStoreHelper.getDefaultAccount(activity) }
            } catch (t: IllegalStateException) {
                Log.e("AccountManager", "Activity not found", t)
                null
            }
            binding.settingsProfilePic.loadImage(currentAccount?.image)
            binding.settingsProfileText.text = currentAccount?.name
        }

        binding.apply {
            // --- MODIFIKASI NOIR: Dialog "Tentang Noir" ---
            appVersionInfo.setOnClickListener {
                val builder = AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
                builder.setTitle("Tentang Noir")
                builder.setMessage("Noir adalah fork dari CloudStream, proyek open-source berlisensi GPLv3 karya Lagradost dan tim.\n\nSeluruh kredit fitur inti adalah milik CloudStream. Lisensi GPLv3 dan pemberitahuan hak cipta upstream wajib dipertahankan pada setiap turunan.\n\nNoir tidak menghosting konten apa pun; aplikasi ini hanya mengindeks sumber pihak ketiga.")
                builder.setNeutralButton("Kunjungi Website") { _, _ ->
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/recloudstream/cloudstream"))
                        startActivity(browserIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                builder.setPositiveButton("Tutup") { dialog, _ -> dialog.dismiss() }
                val dialog: AlertDialog = builder.create()
                dialog.show()

                val webButton: Button? = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                webButton?.let { button ->
                    val fullText = "Kunjungi Website"
                    val spannable = android.text.SpannableString(fullText)
                    spannable.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FF0000")), 0, 8, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 8, fullText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    button.text = spannable
                }
            }

            // KEMBALIKAN settingsExtensions KE DALAM LIST NAVIGASI STANDAR
            listOf(
                settingsGeneral to R.id.action_navigation_global_to_navigation_settings_general,
                settingsPlayer to R.id.action_navigation_global_to_navigation_settings_player,
                settingsCredits to R.id.action_navigation_global_to_navigation_settings_account,
                settingsUi to R.id.action_navigation_global_to_navigation_settings_ui,
                settingsProviders to R.id.action_navigation_global_to_navigation_settings_providers,
                settingsUpdates to R.id.action_navigation_global_to_navigation_settings_updates,
                settingsExtensions to R.id.action_navigation_global_to_navigation_settings_extensions,
            ).forEach { (view, navigationId) ->
                view.apply {
                    setOnClickListener { navigate(navigationId) }
                    if (isLayout(TV)) {
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                }
            }

            // === NOIR: settings jadi PANEL BERIKON (profesional, tanpa emoji) ===
            val panelIcon = mapOf(
                binding.settingsGeneral to R.drawable.ic_baseline_tune_24,
                binding.settingsPlayer to R.drawable.ic_baseline_play_arrow_24,
                binding.settingsCredits to R.drawable.ic_outline_account_circle_24,
                binding.settingsUi to R.drawable.ic_baseline_color_lens_24,
                binding.settingsProviders to R.drawable.ic_baseline_dns_24,
                binding.settingsUpdates to R.drawable.ic_baseline_autorenew_24,
                binding.settingsExtensions to R.drawable.ic_baseline_extension_24,
            )
            panelIcon.forEach { (view, icon) ->
                view.apply {
                    background = com.lagradost.cloudstream3.utils.NoirTheme.glass(
                        context, 18f
                    )
                    val pad = 16.toPx
                    setPadding((pad * 1.2f).toInt(), pad, pad, pad)
                    compoundDrawablePadding = pad
                    setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
                    setCompoundDrawableTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#C7C7CC"))
                    )
                    val lp = layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    lp?.setMargins(20.toPx, 6.toPx, 20.toPx, 6.toPx)
                    layoutParams = lp
                }
            }
            if (isLayout(TV)) settingsGeneral.requestFocus()

            // === NOIR: SEARCH PENGATURAN ===
            // Index semua judul preferensi dari XML (string ter-lokalisasi),
            // petakan ke layar tujuannya; filter live saat user mengetik.
            val ctx = requireContext()
            val index = mutableListOf<Pair<String, Int>>()
            listOf(
                R.xml.settings_general to R.id.action_navigation_global_to_navigation_settings_general,
                R.xml.settings_player to R.id.action_navigation_global_to_navigation_settings_player,
                R.xml.settings_ui to R.id.action_navigation_global_to_navigation_settings_ui,
                R.xml.settings_providers to R.id.action_navigation_global_to_navigation_settings_providers,
                R.xml.settings_updates to R.id.action_navigation_global_to_navigation_settings_updates,
                R.xml.settings_account to R.id.action_navigation_global_to_navigation_settings_account,
            ).forEach { (xmlId, nav) ->
                try {
                    val parser = ctx.resources.getXml(xmlId)
                    var evt = parser.eventType
                    while (evt != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (evt == org.xmlpull.v1.XmlPullParser.START_TAG) {
                            val t = parser.getAttributeValue(
                                "http://schemas.android.com/apk/res/android", "title"
                            )
                            if (t != null && t.startsWith("@")) {
                                t.substring(1).toIntOrNull()?.let { rid ->
                                    runCatching { ctx.getString(rid) }.getOrNull()
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { index += it to nav }
                                }
                            }
                        }
                        evt = parser.next()
                    }
                } catch (_: Exception) { /* layar rusak != app mati */ }
            }
            noirSettingsSearch.background =
                com.lagradost.cloudstream3.utils.NoirTheme.glass(requireContext(), 22f)
            noirSettingsSearch.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(c: CharSequence?, x: Int, y: Int, z: Int) {}
                    override fun onTextChanged(c: CharSequence?, x: Int, y: Int, z: Int) {}
                    override fun afterTextChanged(ed: android.text.Editable?) {
                        val q = ed?.toString()?.trim() ?: ""
                        noirSettingsResults.removeAllViews()
                        if (q.length < 2) {
                            noirSettingsResults.visibility = View.GONE
                            return
                        }
                        val hits = index.filter { it.first.contains(q, true) }
                            .distinctBy { it.first }.take(8)
                        noirSettingsResults.visibility =
                            if (hits.isEmpty()) View.GONE else View.VISIBLE
                        hits.forEach { (title, nav) ->
                            val row = TextView(ctx).apply {
                                text = title
                                setTextColor(Color.WHITE)
                                textSize = 14f
                                setPadding(18.toPx, 12.toPx, 18.toPx, 12.toPx)
                                val tv = android.util.TypedValue()
                                ctx.theme.resolveAttribute(
                                    android.R.attr.selectableItemBackground, tv, true
                                )
                                setBackgroundResource(tv.resourceId)
                                setOnClickListener { v ->
                                    com.lagradost.cloudstream3.utils.NoirHaptic.tap(v)
                                    navigate(nav)
                                }
                            }
                            noirSettingsResults.addView(row)
                        }
                    }
                }
            )
        }

        // ==========================================================
        // --- MODIFIKASI NOIR: Versi, Status Langganan & Device ID ---
        // ==========================================================
        val appVersion = BuildConfig.VERSION_NAME
        val commitInfo = activity?.currentCommitHash() ?: ""
        val buildTimestamp = SimpleDateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG, Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(BuildConfig.BUILD_DATE)).replace("UTC", "")

        binding.appVersion.text = appVersion
        binding.buildDate.text = buildTimestamp
        binding.commitHash.text = commitInfo

        val context = context
        val deviceId = context?.let { DeviceIdentity.getDeviceId(it) } ?: "-"

        context?.let { ctx ->
            val isTvMode = isLayout(TV)

            val profileParent = binding.settingsProfileText.parent as? ViewGroup
            profileParent?.let { pParent ->
                val topTag = "status_tv_tag"
                if (pParent.findViewWithTag<TextView>(topTag) == null) {
                    val tvTopRight = TextView(ctx).apply {
                        tag = topTag
                        text = "ID: $deviceId"
                        textSize = if (isTvMode) 13f else 12f
                        setTextColor(Color.parseColor("#94a3b8"))
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL

                        if (!isTvMode) {
                            setOnClickListener {
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(ctx, "Device ID ($deviceId) berhasil disalin!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    if (pParent is ConstraintLayout) {
                        tvTopRight.id = View.generateViewId()
                        pParent.addView(tvTopRight)
                        val set = ConstraintSet()
                        set.clone(pParent)
                        set.connect(tvTopRight.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 32.toPx)
                        set.connect(tvTopRight.id, ConstraintSet.TOP, binding.settingsProfileText.id, ConstraintSet.TOP)
                        set.connect(tvTopRight.id, ConstraintSet.BOTTOM, binding.settingsProfileText.id, ConstraintSet.BOTTOM)
                        set.applyTo(pParent)
                    } else {
                        binding.settingsProfileText.text = "${binding.settingsProfileText.text}   •   ID: $deviceId"

                        if (!isTvMode) {
                            binding.settingsProfileText.isClickable = true
                            binding.settingsProfileText.setOnClickListener {
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(ctx, "Device ID ($deviceId) berhasil disalin!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

        }

        binding.appVersionInfo.isFocusable = true
        if (isLayout(TV)) {
            binding.settingsExtensions.nextFocusDownId = binding.appVersionInfo.id
            binding.appVersionInfo.nextFocusUpId = binding.settingsExtensions.id
        }

        binding.appVersionInfo.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.setBackgroundColor(Color.parseColor("#1Affffff"))
                view.scaleX = 1.02f
                view.scaleY = 1.02f
            } else {
                view.setBackgroundColor(Color.TRANSPARENT)
                view.scaleX = 1.0f
                view.scaleY = 1.0f
            }
        }

        binding.appVersionInfo.setOnLongClickListener {
            clipboardHelper(
                txt(R.string.extension_version),
                "Device ID: $deviceId\n$appVersion $commitInfo $buildTimestamp"
            )
            true
        }
    }
}
