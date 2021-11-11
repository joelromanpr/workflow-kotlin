package com.squareup.workflow1.ui.container

import com.squareup.workflow1.ui.WorkflowUiExperimentalApi

/**
 * Marker interface implemented by window-like renderings that map to a layer above
 * a base [Screen][com.squareup.workflow1.ui.Screen]. Thinks like alert dialogs, toasts and
 * modals are built this way.
 */
@WorkflowUiExperimentalApi
public interface Overlay
