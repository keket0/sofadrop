package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Focused card (beta) settings for Projectivy: what to show while a movie, series or episode card is focused,
 * what to show on other cards (apps, YouTube, unmatched), and whether that applies to app rows too.
 */
class FocusedCardFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.focused_card_title),
        getString(R.string.focused_card_explain),
        getString(R.string.wallpapers_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    override fun onResume() {
        super.onResume()
        refreshActions(rows())
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val list = mutableListOf<GuidedAction>()

        val labels = Settings.FOCUSED_OPTIONS.map { modeLabel(context, it) }
        val currentMode = modeLabel(context, Settings.focusedCard)
        list += action(ID_MODE, R.drawable.ic_act_play, getString(R.string.focused_card_mode_title), currentMode)
            .subActions(options(ID_MODE, labels, currentMode,
                Settings.FOCUSED_OPTIONS.map { if (it == Settings.FOCUSED_EPISODE) getString(R.string.focused_card_episode_desc) else null })).build()

        if (Settings.focusedCard != Settings.FOCUSED_OFF) {
            explanations[ID_FALLBACK] = getString(R.string.focused_fallback_explain)
            val fallbackLabels = Settings.FOCUSED_FALLBACK_OPTIONS.map { fallbackLabel(context, it) }
            val currentFallback = fallbackLabel(context, Settings.focusedFallback)
            list += action(ID_FALLBACK, R.drawable.ic_act_layout, getString(R.string.focused_fallback_title), currentFallback)
                .subActions(options(ID_FALLBACK, fallbackLabels, currentFallback,
                    Settings.FOCUSED_FALLBACK_OPTIONS.map { fallbackDesc(it) })).build()

            if (Settings.focusedFallback != Settings.FOCUSED_FALLBACK_SET) {
                explanations[ID_SCOPE] = getString(R.string.focused_scope_explain)
                val scopeLabels = Settings.FOCUSED_SCOPE_OPTIONS.map { scopeLabel(it) }
                val currentScope = scopeLabel(Settings.focusedFallbackScope)
                list += action(ID_SCOPE, R.drawable.ic_act_filter, getString(R.string.focused_scope_title), currentScope)
                    .subActions(options(ID_SCOPE, scopeLabels, currentScope,
                        Settings.FOCUSED_SCOPE_OPTIONS.map { scopeDesc(it) })).build()
            }
        }

        val cards = FocusedLog.read(context).size
        if (cards > 0 || Settings.focusedCard != Settings.FOCUSED_OFF) {
            explanations[ID_CARD_LOG] = getString(R.string.focused_log_explain)
            list += action(ID_CARD_LOG, R.drawable.ic_act_status, getString(R.string.focused_log_title),
                if (cards == 0) getString(R.string.focused_log_none) else resources.getQuantityString(R.plurals.focused_log_cards, cards, cards))
                .hasNext(cards > 0).enabled(cards > 0).build()
        }
        return list
    }

    private fun fallbackDesc(mode: String): String? = when (mode) {
        Settings.FOCUSED_FALLBACK_DIMMED -> getString(R.string.focused_fallback_dimmed_desc)
        else -> null
    }

    private fun scopeLabel(scope: String) = getString(when (scope) {
        Settings.FOCUSED_SCOPE_ALL -> R.string.focused_scope_all
        else -> R.string.focused_scope_channels
    })

    private fun scopeDesc(scope: String) = getString(when (scope) {
        Settings.FOCUSED_SCOPE_ALL -> R.string.focused_scope_all_desc
        else -> R.string.focused_scope_channels_desc
    })

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_CARD_LOG) open(FocusedLogFragment())
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        when (action.id / SUB_ID_BASE) {
            ID_MODE -> {
                Settings.focusedCard = Settings.FOCUSED_OPTIONS[(action.id % SUB_ID_BASE).toInt()]
                collapseSubActions()
                refreshActions(rows())
                return true
            }
            ID_FALLBACK -> {
                Settings.focusedFallback = Settings.FOCUSED_FALLBACK_OPTIONS[(action.id % SUB_ID_BASE).toInt()]
                collapseSubActions()
                refreshActions(rows())
                return true
            }
            ID_SCOPE -> {
                Settings.focusedFallbackScope = Settings.FOCUSED_SCOPE_OPTIONS[(action.id % SUB_ID_BASE).toInt()]
                refreshActions(rows())
                return true
            }
        }
        return false
    }

    companion object {
        private const val ID_MODE = 1L
        private const val ID_FALLBACK = 2L
        private const val ID_SCOPE = 3L
        private const val ID_CARD_LOG = 4L

        private fun modeLabel(context: Context, mode: String) = context.getString(when (mode) {
            Settings.FOCUSED_EPISODE -> R.string.focused_card_episode
            Settings.FOCUSED_EPISODE_SERIES_IMAGE -> R.string.focused_card_episode_series_image
            Settings.FOCUSED_SERIES -> R.string.focused_card_series
            else -> R.string.focused_card_off
        })

        private fun fallbackLabel(context: Context, mode: String) = context.getString(when (mode) {
            Settings.FOCUSED_FALLBACK_DARK -> R.string.focused_fallback_dark
            Settings.FOCUSED_FALLBACK_DIMMED -> R.string.focused_fallback_dimmed
            else -> R.string.focused_fallback_set
        })

        /** One-line summary for [WallpapersFragment]. */
        fun summary(context: Context): String {
            val mode = Settings.focusedCard
            if (mode == Settings.FOCUSED_OFF) return modeLabel(context, mode)
            val fallback = Settings.focusedFallback
            return if (fallback == Settings.FOCUSED_FALLBACK_SET) modeLabel(context, mode)
            else "${modeLabel(context, mode)} · ${fallbackLabel(context, fallback)}"
        }
    }
}
