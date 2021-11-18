package com.squareup.workflow1.ui.container

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import androidx.lifecycle.Lifecycle.Event.ON_DESTROY
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.squareup.workflow1.ui.Compatible
import com.squareup.workflow1.ui.ViewEnvironment
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import com.squareup.workflow1.ui.androidx.WorkflowLifecycleOwner
import com.squareup.workflow1.ui.compatible

@WorkflowUiExperimentalApi
internal class DialogRunner<T : Overlay>(
  initialRendering: T,
  initialViewEnvironment: ViewEnvironment,
  private val context: Context,
  private val factory: OverlayDialogFactory<T>
) {

  var rendering: T = initialRendering
    private set

  var environment: ViewEnvironment = initialViewEnvironment
    private set

  private var dialogOrNull: Dialog? = null

  fun show(parentLifecycleOwner: LifecycleOwner?) {
    requireDialog().let { dialog ->
      dialog.show()
      dialog.window?.decorView?.also { decorView ->
        // Implementations of buildDialog may set their own WorkflowLifecycleOwner on the
        // content view, so to avoid interfering with them we also set it here. When the views
        // are attached, this will become the parent lifecycle of the one from buildDialog if
        // any, and so we can use our lifecycle to destroy-on-detach the dialog hierarchy.
        WorkflowLifecycleOwner.installOn(
          decorView,
          findParentLifecycle = { parentLifecycleOwner?.lifecycle }
        )

        decorView.doOnAttach {
          val lifecycle = parentLifecycleOwner?.lifecycle ?: return@doOnAttach
          val onDestroy = OnDestroy { dismiss() }

          // Android makes a lot of logcat noise if it has to close the window for us. :/
          // And no, we can't call ref.dismiss() directly from the doOnDetach lambda,
          // that's too late.
          // https://github.com/square/workflow/issues/51
          lifecycle.addObserver(onDestroy)

          // Note that we are careful not to make the doOnDetach call unless
          // we actually get attached. It is common for the dialog to be dismissed
          // before it is ever shown, so doOnDetach would never fire and we'd leak the
          // onDestroy lambda.
          decorView.doOnDetach { lifecycle.removeObserver(onDestroy) }
        }
      }
    }
  }

  fun dismiss() {
    // The dialog's views are about to be detached, and when that happens we want to transition
    // the dialog view's lifecycle to a terminal state even though the parent is probably still
    // alive.
    dialogOrNull?.let { dialog ->
      dialog.window?.decorView?.let(WorkflowLifecycleOwner::get)?.destroyOnDetach()
      dialog.dismiss()
    }
  }

  fun canShowRendering(rendering: Overlay): Boolean {
    return compatible(this.rendering, rendering)
  }

  fun showRendering(
    rendering: Overlay,
    environment: ViewEnvironment
  ) {
    check(canShowRendering(rendering)) {
      "Expected $this to be able to show rendering $rendering, but that did not match " +
        "previous rendering ${this.rendering}."
    }

    @Suppress("UNCHECKED_CAST")
    this.rendering = rendering as T
    this.environment = environment

    dialogOrNull?.let { dialog ->
      factory.updateDialog(dialog, this.rendering, this.environment)
    }
  }

  internal fun save(): KeyAndBundle? {
    val saved = dialogOrNull?.window?.saveHierarchyState() ?: return null
    return KeyAndBundle(Compatible.keyFor(rendering), saved)
  }

  internal fun restore(keyAndBundle: KeyAndBundle) {
    if (Compatible.keyFor(rendering) == keyAndBundle.compatibilityKey) {
      requireDialog().window?.restoreHierarchyState(keyAndBundle.bundle)
    }
  }

  private fun requireDialog(): Dialog {
    return dialogOrNull ?: factory.buildDialog(rendering, environment, context)
      .also {
        dialogOrNull = it
        showRendering(rendering, environment)
      }
  }

  internal data class KeyAndBundle(
    internal val compatibilityKey: String,
    internal val bundle: Bundle
  ) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(
      parcel: Parcel,
      flags: Int
    ) {
      parcel.writeString(compatibilityKey)
      parcel.writeBundle(bundle)
    }

    companion object CREATOR : Creator<KeyAndBundle> {
      override fun createFromParcel(parcel: Parcel): KeyAndBundle {
        val key = parcel.readString()!!
        val bundle = parcel.readBundle(KeyAndBundle::class.java.classLoader)!!
        return KeyAndBundle(key, bundle)
      }

      override fun newArray(size: Int): Array<KeyAndBundle?> = arrayOfNulls(size)
    }
  }

  private class OnDestroy(private val block: () -> Unit) : LifecycleObserver {
    @OnLifecycleEvent(ON_DESTROY)
    fun onDestroy() = block()
  }
}
