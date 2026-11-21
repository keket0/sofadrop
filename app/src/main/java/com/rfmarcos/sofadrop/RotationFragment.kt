package com.rfmarcos.sofadrop

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Which positions a rotating text goes through (see [Settings.POSITION_ROTATE]): fixed ones and the user's own. */
class RotationFragment : BaseSettingsFragment() {

    /** The user's own positions in the rotation when the rows were made: unticking one takes it out. */
    private var customs: List<String> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.position_rotation_title),
        getString(R.string.position_rotation_desc),
        getString(R.string.position_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    // Back from adding one
    override fun onResume() {
        super.onResume()
        refreshActions(rows())
    }

    private fun rows(): List<GuidedAction> {
        val chosen = profile.rotation
        customs = chosen.filter { Settings.customPoint(it) != null }
        val fixed = Settings.POSITIONS.mapIndexed { index, position ->
            val covered = position.startsWith("bottom") && !profile.isScreensaver && Projectivy.isInstalled(requireContext())
            checkbox(index.toLong() + 1, DetailsFragment.positionLabel(requireContext(), position),
                getString(R.string.position_rows_note).takeIf { covered }, position in chosen).build()
        }
        val own = customs.mapIndexed { index, position ->
            val (x, y) = Settings.pointOf(position)
            checkbox(ID_CUSTOM + index, getString(R.string.position_custom_point, x, y), null, true).build()
        }
        return fixed + own + action(ID_ADD, R.drawable.ic_act_place, getString(R.string.position_custom_add),
            getString(R.string.position_custom_desc)).build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_ADD) {
            startActivity(Intent(requireContext(), PositionActivity::class.java)
                .putExtra(PositionActivity.EXTRA_PROFILE, profile.id).putExtra(PositionActivity.EXTRA_ROTATION, true))
            return
        }
        val chosen = Settings.POSITIONS.filterIndexed { index, _ -> findActionById(index.toLong() + 1)?.isChecked == true } +
            customs.filterIndexed { index, _ -> findActionById(ID_CUSTOM + index)?.isChecked == true }
        if (chosen.size < 2) {
            action.isChecked = true
            notifyActionChanged(findActionPositionById(action.id))
            Toast.makeText(requireContext(), R.string.position_rotation_min, Toast.LENGTH_SHORT).show()
        } else {
            profile.rotation = chosen
        }
    }

    companion object {
        private const val ID_CUSTOM = 100L
        private const val ID_ADD = 99L
    }
}
