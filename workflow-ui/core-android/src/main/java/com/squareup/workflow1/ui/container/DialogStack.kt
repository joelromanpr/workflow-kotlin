package com.squareup.workflow1.ui.container

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.squareup.workflow1.ui.ViewEnvironment
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import com.squareup.workflow1.ui.androidx.WorkflowLifecycleOwner
import com.squareup.workflow1.ui.container.DialogRunner.KeyAndBundle

/**
 * Does the bulk of the work of maintaining a set of [Dialog][android.app.Dialog]s
 * to reflect lists of [Overlay]. Can be used to create custom [Overlay]-based
 * layouts if [BodyAndModalsScreen] or the default [View] bound to it are too restrictive.
 * Provides a [LifecycleOwner] per managed dialog, and view persistence support.
 */
@WorkflowUiExperimentalApi
public class DialogStack(
  private val context: Context,
  private val getParentLifecycleOwner: () -> LifecycleOwner?
) {
  /**
   * Builds a [DialogStack] which looks through [view] to find its parent
   * [LifecycleOwner][getParentLifecycleOwner].
   */
  public constructor(view: View) : this(view.context, { WorkflowLifecycleOwner.get(view) })

  private var runners: List<DialogRunner<*>> = emptyList()

  /** True when any dialogs are visible, or becoming visible. */
  public val hasDialogs: Boolean = runners.isNotEmpty()

  /**
   * Updates the managed set of [Dialog][android.app.Dialog] instances to reflect
   * [overlays]. Opens new dialogs, updates existing ones, and dismisses those
   * that match no member of that list.
   *
   * Each dialog has its own [WorkflowLifecycleOwner], which starts when the dialog
   * is shown, and is destroyed when it is dismissed. Views nested in a managed dialog
   * can use [ViewTreeLifecycleOwner][androidx.lifecycle.ViewTreeLifecycleOwner] as
   * usual.
   */
  public fun update(
    overlays: List<Overlay>,
    viewEnvironment: ViewEnvironment,
    beforeShowing: () -> Unit = {}
  ) {
    // Any nested back stacks have to provide saved state registries of their
    // own, but these things share a global namespace. To make that practical
    // we add uniquing strings to the ViewEnvironment for each dialog,
    // via withBackStackStateKeyPrefix.

    val overlayEnvironments = overlays.mapIndexed { index, _ ->
      viewEnvironment.withBackStackStateKeyPrefix("[${index + 1}]")
    }

    // On each update we build a new list of the running dialogs, both the
    // existing ones and any new ones. We need this so that we can compare
    // it with the previous list, and see what dialogs close.
    val newRunners = mutableListOf<DialogRunner<*>>()

    for ((i, overlay) in overlays.withIndex()) {
      val overlayEnvironment = overlayEnvironments[i]

      newRunners += if (i < runners.size && runners[i].canShowRendering(overlay)) {
        // There is already a dialog at this index, and it is compatible
        // with the new Overlay at that index. Just update it.
        runners[i].also { it.showRendering(overlay, overlayEnvironment) }
      } else {
        // We need a new dialog for this overlay. Time to build it.
        // We wrap our Dialog instances in DialogRunner to keep them
        // paired with their current overlay rendering and environment.
        // It would have been nice to keep those in tags on the Dialog's
        // decor view, more consistent with what ScreenViewFactory does,
        // but calling Window.getDecorView has side effects, and things
        // break if we call it to early. Need to store them somewhere else.
        overlay.toDialogFactory(overlayEnvironment).let { dialogFactory ->
          DialogRunner(
            overlay, overlayEnvironment, context, dialogFactory
          ).also { newRunner ->
            // Has the side effect of creating the dialog if necessary.
            newRunner.showRendering(overlay, overlayEnvironment)
            // Custom behavior from the container to be fired when we show a new dialog.
            // The default modal container uses this to flush its body of partially
            // processed touch events that should have been blocked by the modal.
            beforeShowing()
            // Show the dialog. We use the container-provided LifecycleOwner
            // to host an androidx ViewTreeLifecycleOwner and SavedStateRegistryOwner
            // for each dialog. These are generally expected these days, and absolutely
            // required by Compose.
            newRunner.show(getParentLifecycleOwner())
          }
        }
      }
    }

    (runners - newRunners.toSet()).forEach { it.dismiss() }
    runners = newRunners
    // TODO Smarter diffing, and Z order. Maybe just hide and show everything on every update?
  }

  /** To be called from a container view's [View.onSaveInstanceState]. */
  public fun onSaveInstanceState(): SavedState {
    return SavedState(runners.mapNotNull { it.save() })
  }

  /** To be called from a container view's [View.onRestoreInstanceState]. */
  public fun onRestoreInstanceState(state: SavedState) {
    if (state.dialogBundles.size == runners.size) {
      state.dialogBundles.zip(runners) { viewState, runner -> runner.restore(viewState) }
    }
  }

  public class SavedState : Parcelable {
    internal val dialogBundles: List<KeyAndBundle>

    internal constructor(dialogBundles: List<KeyAndBundle>) {
      this.dialogBundles = dialogBundles
    }

    public constructor(source: Parcel) {
      dialogBundles = mutableListOf<KeyAndBundle>().apply {
        source.readTypedList(this, KeyAndBundle)
      }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(
      out: Parcel,
      flags: Int
    ) {
      out.writeTypedList(dialogBundles)
    }

    public companion object CREATOR : Creator<SavedState> {
      override fun createFromParcel(source: Parcel): SavedState =
        SavedState(source)

      override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
    }
  }
}
