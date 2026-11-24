package com.rfmarcos.sofadrop

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The main screen, the same for everybody: the two places the wallpapers show, each of which can use its own
 * settings, then the general settings they share by default.
 */
class SettingsFragment : PreviewSettingsFragment() {

    /** Something shows the wallpapers: Projectivy, or another launcher through the folder. */
    private val hasProjectivy get() = WallpaperFolder.shown(requireContext())

    override val showsProjectivyNotice get() = Projectivy.isInstalled(requireContext()) && Projectivy.inUse(requireContext())

    private var startPlaced = false
    private var supportCounted = false
    /** Created only in Google Play builds while the top support prompt is shown. */
    private var supporter: Supporter? = null

    private val statusRefresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        getString(R.string.plugin_name),
        "",
        getString(R.string.settings),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Delivered on the way back from the notes, before onResume resets the update row: install after it
        parentFragmentManager.setFragmentResultListener(UpdateFragment.RESULT_INSTALL, this) { _, _ -> handler.post { installUpdate() } }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        Settings.init(context)

        if (Updater.available) actions += updateAction()
        else if (SupportPrompt.shouldShow(updateAvailable = false)) {
            if (BuildConfig.PLAY && supporter == null) supporter = createSupporter(context, ::showSupporter)
            if (!supportCounted) {
                supportCounted = true
                Settings.supportShownCount += 1
            }
            actions += supportAction()
        }
        // "Update now" also carries the status, so there is a single place to look
        actions += action(ID_GENERATE, R.drawable.ic_act_refresh, getString(R.string.action_generate_now), topStatus())
            .multilineDescription(true).build()
        actions += action(ID_WALLPAPERS, R.drawable.ic_act_tv, getString(R.string.wallpapers_title), WallpapersFragment.summary(context))
            .multilineDescription(true).hasNext(true).build()
        actions += action(ID_SCREENSAVER, R.drawable.ic_act_screensaver, getString(R.string.screensaver_title), ScreensaverFragment.summary(context))
            .multilineDescription(true).hasNext(true).build()

        actions += header(ID_HEADER_GENERAL, getString(R.string.general_header))
        actions += action(ID_LISTS, R.drawable.ic_act_movie, getString(R.string.setting_lists_title), listsText())
            .hasNext(true).build()
        actions += action(ID_DETAILS, R.drawable.ic_act_visibility, getString(R.string.setting_details_title), detailsText())
            .hasNext(true).build()
        actions += action(ID_CLICK, R.drawable.ic_act_play, getString(R.string.click_action_title), clickText())
            .hasNext(true).build()
        actions += choice(ID_ORDER, R.drawable.ic_act_shuffle, R.string.screensaver_order_title, orderText(),
            listOf(true, false).map { OutputFragment.mixLabel(context, it) })

        actions += header(ID_HEADER_MORE, getString(R.string.main_more_header))
        actions += choice(ID_REFRESH, R.drawable.ic_act_schedule, R.string.setting_refresh_title, refreshLabel(Settings.refreshHours),
            Settings.REFRESH_OPTIONS.map(::refreshLabel))
        actions += action(ID_ACCOUNTS, R.drawable.ic_act_account, getString(R.string.setting_accounts_title), AccountsFragment.summary(context))
            .hasNext(true).build()
        actions += action(ID_ABOUT, R.drawable.ic_act_info, getString(R.string.setting_about_title),
            getString(R.string.setting_about_desc, BuildConfig.VERSION_NAME)).hasNext(true).build()
    }

    // The left pane's text only shows with the row that explains itself
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guidanceStylist.descriptionView?.visibility = android.view.View.VISIBLE
        // The status is there to be read: start on the first place, not on a row that regenerates everything.
        // Without Projectivy, Wallpapers only offers to get it: start where there's something to do.
        val start = if (hasProjectivy) ID_WALLPAPERS.takeIf { !Updater.available } else ID_SCREENSAVER
        // Only the first time: coming back from a sub-screen creates the view again, and keeps its place
        if (savedInstanceState == null && !startPlaced && start != null) handler.post {
            startPlaced = true
            if (isAdded) findActionPositionById(start).takeIf { it >= 0 }?.let(::setSelectedActionPosition)
        }
    }

    override fun onResume() {
        super.onResume()
        // Sub-screens may have changed lists, filters or details
        val context = requireContext()
        updateDescription(ID_WALLPAPERS, WallpapersFragment.summary(context))
        updateDescription(ID_SCREENSAVER, ScreensaverFragment.summary(context))
        updateDescription(ID_LISTS, listsText())
        updateDescription(ID_DETAILS, detailsText())
        updateDescription(ID_CLICK, clickText())
        updateDescription(ID_ORDER, orderText())
        updateDescription(ID_ACCOUNTS, AccountsFragment.summary(context))
        handler.post(statusRefresher)
        // Back from the system installer (cancelled, or blocked by Play Protect): offer the update again; back from
        // the settings without allowing Sofadrop to install apps: where to allow it
        val allowed = !askedToAllow || requireContext().packageManager.canRequestPackageInstalls()
        updateDescription(ID_APP_UPDATE, if (allowed) updateText() else allowText())
        Updater.installListener = installListener
        Updater.unheardFailure?.let { Updater.unheardFailure = null; installListener.failed(it) }
        background({ Updater.check() }) { showUpdate() }
        if (findActionById(ID_SUPPORT_PROMPT) != null) {
            if (Settings.supportDismissed || (BuildConfig.PLAY && Settings.supporterBought)) removeSupportPrompt()
            else supporter?.refresh()
        }
    }

    override fun onDestroy() {
        supporter?.close()
        supporter = null
        super.onDestroy()
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
        handler.removeCallbacks(confirmMissing)
        if (Updater.installListener === installListener) Updater.installListener = null
        val context = requireContext()
        GenerateWallpapersWorker.schedule(context, settingsChanged = true)
        // First run: nothing to show in Projectivy yet, so generate right away (the wizard does it with its answers)
        if (shownSet.lastGeneration == 0L && Settings.token.isNotBlank() && !Wizard.open) GenerateWallpapersWorker.runNow(context, shownSet)
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_GENERATE -> {
                if ((if (Profile.twoSets) listOf(Profile.WALLPAPER, Profile.SCREENSAVER) else listOf(shownSet)).all { Sources.selected(it).isEmpty() }) {
                    Toast.makeText(requireContext(), R.string.select_list_first, Toast.LENGTH_SHORT).show()
                    return
                }
                GenerateWallpapersWorker.runAll(requireContext())
                Toast.makeText(requireContext(), R.string.generation_started, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
            // What's new first, when the release says
            ID_APP_UPDATE -> if (hasNotes()) open(UpdateFragment()) else installUpdate()
            ID_LISTS -> open(ListsFragment())
            ID_DETAILS -> open(DetailsFragment())
            ID_WALLPAPERS -> add(parentFragmentManager, WallpapersFragment().forProfile(Profile.WALLPAPER))
            ID_SCREENSAVER -> add(parentFragmentManager, ScreensaverFragment().forProfile(Profile.SCREENSAVER))
            ID_CLICK -> open(ClickActionFragment())
            ID_ACCOUNTS -> open(AccountsFragment())
            ID_ABOUT -> open(AboutFragment())
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_ORDER -> {
                Settings.mixLists = index == 0
                updateDescription(ID_ORDER, orderText())
            }
            ID_REFRESH -> {
                Settings.refreshHours = Settings.REFRESH_OPTIONS[index]
                updateDescription(ID_REFRESH, refreshLabel(Settings.refreshHours))
                refreshStatus()
            }
            ID_SUPPORT_PROMPT -> {
                if (index == 0) {
                    if (supporter != null) supporter?.buy(requireActivity())
                    else if (BuildConfig.SUPPORT_URL.isNotBlank()) {
                        LinkQrDialog.newInstance(BuildConfig.SUPPORT_URL, R.string.about_support, R.string.about_support_scan)
                            .show(parentFragmentManager, "support")
                    }
                } else {
                    Settings.supportDismissed = true
                    Toast.makeText(requireContext(), R.string.support_prompt_hidden, Toast.LENGTH_SHORT).show()
                    handler.post { if (isAdded) removeSupportPrompt() }
                }
            }
        }
        return true
    }

    private fun refreshLabel(hours: Int) =
        if (hours < 48) getString(R.string.refresh_hours, hours) else getString(R.string.refresh_days, hours / 24)

    /** [text], saying so when every place that shows wallpapers has its [own] instead of this general setting. */
    private fun general(id: Long, text: String, own: (Profile) -> Boolean): String {
        val unused = own(Profile.SCREENSAVER) && (!hasProjectivy || own(Profile.WALLPAPER))
        if (unused) {
            explanations[id] = getString(if (hasProjectivy) R.string.general_unused_explain else R.string.general_unused_explain_alone)
            unusedRows += id
        } else {
            explanations.remove(id)
            unusedRows -= id
        }
        return if (unused) getString(R.string.general_unused, text) else text
    }

    private fun listsText() = general(ID_LISTS, ListsFragment.summary(requireContext(), profile)) { it.ownLists }

    private fun detailsText() = general(ID_DETAILS, DetailsFragment.summary(requireContext(), profile)) { it.ownLook }

    private fun clickText() = general(ID_CLICK, ClickActionFragment.summary(requireContext(), profile)) { it.ownClick }

    private fun orderText() = general(ID_ORDER, OutputFragment.mixLabel(requireContext(), Settings.mixLists)) { it.ownMix != null }

    private fun hasNotes() = Updater.releaseNotes(Settings.updateNotes).isNotEmpty()

    private fun updateText() = getString(if (hasNotes()) R.string.update_desc_notes else R.string.update_desc,
        Settings.updateVersion, BuildConfig.VERSION_NAME)

    // Opens the notes first when the release has them (onGuidedActionClicked)
    private fun updateAction(): GuidedAction =
        action(ID_APP_UPDATE, R.drawable.ic_act_refresh, getString(R.string.update_title), updateText()).multilineDescription(true).build()

    private fun supportAction(): GuidedAction {
        val context = requireContext()
        val description = getString(if (BuildConfig.PLAY) R.string.supporter_desc else R.string.about_support_desc)
        val openLabel = getString(if (BuildConfig.PLAY) R.string.support_prompt_supporter else R.string.support_prompt_kofi)
        val hideLabel = getString(R.string.support_prompt_hide)
        return action(ID_SUPPORT_PROMPT, R.drawable.ic_act_heart, getString(R.string.about_support), description)
            .multilineDescription(true)
            .subActions(listOf(
                GuidedAction.Builder(context).id(ID_SUPPORT_PROMPT * SUB_ID_BASE).title(openLabel).build(),
                GuidedAction.Builder(context).id(ID_SUPPORT_PROMPT * SUB_ID_BASE + 1).title(hideLabel).build(),
            )).build()
    }

    private fun removeSupportPrompt() {
        if (findActionById(ID_SUPPORT_PROMPT) == null) return
        actions = actions.filterNot { it.id == ID_SUPPORT_PROMPT }
    }

    private fun showSupporter(state: Supporter.State) {
        if (state == Supporter.State.UNAVAILABLE) {
            if (isAdded) Toast.makeText(requireContext(), R.string.supporter_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        Supporter.remember(state)
        if (!isAdded) return
        if (state == Supporter.State.BOUGHT || state == Supporter.State.PENDING) {
            removeSupportPrompt()
        }
    }

    /** Offer an update found after the screen opened, on top, without reopening the screen. */
    private fun showUpdate() {
        if (!isAdded || !Updater.available || findActionById(ID_APP_UPDATE) != null) return
        actions = actions.filterNot { it.id == ID_SUPPORT_PROMPT }.toMutableList().apply { add(0, updateAction()) }
    }

    /**
     * Download and install the new version; Android first asks to allow Sofadrop to install apps. Whatever stops
     * it is said on the update row, with the other way to update (Downloader).
     */
    private fun installUpdate() {
        val context = requireContext().applicationContext
        if (!context.packageManager.canRequestPackageInstalls()) {
            askedToAllow = true
            updateDescription(ID_APP_UPDATE, allowText())
            // Not every TV has the per-app screen: then the list of apps, then the security settings
            val app = android.net.Uri.parse("package:${context.packageName}")
            val opened = listOf(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, app),
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
            ).any { intent -> runCatching { startActivity(intent) }.isSuccess }
            if (opened) Toast.makeText(context, R.string.update_allow, Toast.LENGTH_LONG).show()
            return
        }
        askedToAllow = false
        updateDescription(ID_APP_UPDATE, getString(R.string.update_downloading, Settings.updateVersion))
        background({ UpdateInstaller.install(context) }) { result ->
            result.onFailure { installListener.failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    /** Sofadrop was sent to allow installing apps: until it is, the update row says where. */
    private var askedToAllow = false

    private fun allowText() = withDownloader(getString(R.string.update_allow_where))

    private fun withDownloader(text: String) =
        Updater.downloaderCode?.let { text + "\n" + getString(R.string.update_downloader, it) } ?: text

    /**
     * What comes of an install while this screen is open: Android's confirmation starts from here, an activity
     * on screen; started from the background it may not show. If this screen is still in front a while later,
     * it didn't: the row says so.
     */
    private val installListener = object : Updater.InstallListener {
        override fun confirm(intent: Intent) {
            try {
                startActivity(intent)
            } catch (e: Exception) {
                failed(e.message ?: e.javaClass.simpleName)
                return
            }
            handler.postDelayed(confirmMissing, CONFIRM_WAIT_MILLIS)
        }

        override fun failed(message: String) {
            handler.removeCallbacks(confirmMissing)
            if (isAdded) updateDescription(ID_APP_UPDATE, withDownloader(getString(R.string.update_failed, message)))
        }
    }

    /** Cancelled by [onPause]: the confirmation came up. */
    private val confirmMissing = Runnable {
        if (isResumed) updateDescription(ID_APP_UPDATE, withDownloader(getString(R.string.update_no_confirm)))
    }

    // --- Status and pending changes ---

    private fun refreshStatus() {
        if (!isAdded) return
        updateDescription(ID_GENERATE, topStatus())
        refreshPending()
    }

    /** With two sets, the one that needs a look: this row is the only status on the way in. */
    private fun topStatus(): String = generationStatus(
        listOf(Profile.WALLPAPER, Profile.SCREENSAVER).map { it.source }
            .firstOrNull { hasProjectivy && it != shownSet && (it.generating || it.lastError.isNotEmpty()) } ?: shownSet
    )

    companion object {
        private const val ID_GENERATE = 2L
        private const val ID_LISTS = 4L
        private const val ID_REFRESH = 8L
        private const val ID_ABOUT = 18L
        private const val ID_DETAILS = 20L
        private const val ID_APP_UPDATE = 21L
        private const val ID_ACCOUNTS = 23L
        private const val ID_CLICK = 24L
        private const val ID_SCREENSAVER = 25L
        private const val ID_HEADER_GENERAL = 26L
        private const val ID_HEADER_MORE = 27L
        private const val ID_WALLPAPERS = 28L
        private const val ID_ORDER = 29L
        private const val ID_SUPPORT_PROMPT = 30L
        /** Android's install confirmation takes a moment on a slow TV; not up by then, it didn't come. */
        private const val CONFIRM_WAIT_MILLIS = 10_000L
    }
}
