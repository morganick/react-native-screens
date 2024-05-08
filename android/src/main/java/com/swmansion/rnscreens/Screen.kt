package com.swmansion.rnscreens

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Paint
import android.os.Parcelable
import android.util.Log
import android.util.SparseArray
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.children
import androidx.fragment.app.Fragment
import com.facebook.react.bridge.GuardedRunnable
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.PixelUtil
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.UIManagerModule
import com.facebook.react.uimanager.events.EventDispatcher
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.State
import com.swmansion.rnscreens.events.HeaderHeightChangeEvent
import com.swmansion.rnscreens.events.SheetDetentChangedEvent

@SuppressLint("ViewConstructor")
class Screen(context: ReactContext) : FabricEnabledViewGroup(context), ScreenContentWrapper.OnLayoutCallback {
    val fragment: Fragment?
        get() = fragmentWrapper?.fragment

    val sheetBehavior: BottomSheetBehavior<Screen>?
        get() = (layoutParams as? CoordinatorLayout.LayoutParams)?.behavior as? BottomSheetBehavior<Screen>

    val reactContext: ReactContext = context
    val reactEventDispatcher: EventDispatcher?
        get() = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)

    var fragmentWrapper: ScreenFragmentWrapper? = null
    var container: ScreenContainer? = null
    var activityState: ActivityState? = null
        private set
    private var isTransitioning = false
    var stackPresentation = StackPresentation.PUSH
    var replaceAnimation = ReplaceAnimation.POP
    var stackAnimation = StackAnimation.DEFAULT
    var isGestureEnabled = true
    var screenOrientation: Int? = null
        private set
    var isStatusBarAnimated: Boolean? = null

    // Props for controlling modal presentation
    var isSheetGrabberVisible: Boolean = false
    var sheetCornerRadius: Float = 0F
        set(value) {
            field = value
            (fragment as? ScreenStackFragment)?.onSheetCornerRadiusChange()
        }
    var sheetExpandsWhenScrolledToEdge: Boolean = true
    
    // We want to make sure here that at least one value is present in this array all the time.
    // TODO: Model this with custom data structure to guarantee that this invariant is not violated.
    var sheetDetents = ArrayList<Double>().apply { add(1.0) }
    var sheetLargestUndimmedDetentIndex: Int = -1
    var sheetInitialDetentIndex: Int = 0
    var sheetClosesOnTouchOutside = true
    var sheetElevation: Float = 24F

    var footer: ScreenFooter? = null
        set(value) {
            if (value == null && field != null) {
                sheetBehavior?.let { field!!.unregisterWithSheetBehaviour(it) }
            } else if (value != null) {
                sheetBehavior?.let { value.registerWithSheetBehaviour(it) }
            }
            field = value
        }

    init {
        // we set layout params as WindowManager.LayoutParams to workaround the issue with TextInputs
        // not displaying modal menus (e.g., copy/paste or selection). The missing menus are due to the
        // fact that TextView implementation is expected to be attached to window when layout happens.
        // Then, at the moment of layout it checks whether window type is in a reasonable range to tell
        // whether it should enable selection controls (see Editor.java#prepareCursorControllers).
        // With screens, however, the text input component can be laid out before it is attached, in
        // that case TextView tries to get window type property from the oldest existing parent, which
        // in this case is a Screen class, as it is the root of the screen that is about to be attached.
        // Setting params this way is not the most elegant way to solve this problem but workarounds it
        // for the time being
        layoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.TYPE_APPLICATION)
    }


    /**
     * ScreenContentWrapper notifies us here on it's layout. It is essential for implementing
     * `fitToContents` for formSheets, as this is first entry point where we can acquire
     * height of our content.
     */
    override fun onLayoutCallback(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val width = right - left
        val height = bottom - top

        if (sheetDetents.count() == 1 && sheetDetents.first() == SHEET_FIT_TO_CONTENTS) {
            sheetBehavior?.let {
                if (it.maxHeight != height) {
                    it.maxHeight = height
                }
            }
        }
    }

    fun registerLayoutCallbackForWrapper(wrapper: ScreenContentWrapper) {
        wrapper.delegate = this
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets {
        return super.onApplyWindowInsets(insets)
    }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) {
        // do nothing, react native will keep the view hierarchy so no need to serialize/deserialize
        // view's states. The side effect of restoring is that TextInput components would trigger
        // set-text events which may confuse text input handling.
    }

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) {
        // ignore restoring instance state too as we are not saving anything anyways.
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (container is ScreenStack && changed) {
            val width = r - l
            val height = b - t

            val headerHeight = calculateHeaderHeight()
            val totalHeight = headerHeight.first + headerHeight.second // action bar height + status bar height
            if (BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
                updateScreenSizeFabric(width, height, totalHeight)
            } else {
                updateScreenSizePaper(width, height)
            }

            footer?.onParentLayout(changed, l, t, r, b, container!!.height)
            notifyHeaderHeightChange(totalHeight)
        }
    }

    private fun updateScreenSizePaper(
        width: Int,
        height: Int,
    ) {
        val reactContext = context as ReactContext
        reactContext.runOnNativeModulesQueueThread(
            object : GuardedRunnable(reactContext) {
                override fun runGuarded() {
                    reactContext
                        .getNativeModule(UIManagerModule::class.java)
                        ?.updateNodeSize(id, width, height)
                }
            },
        )
    }

    val headerConfig: ScreenStackHeaderConfig?
        get() = children.find { it is ScreenStackHeaderConfig } as? ScreenStackHeaderConfig

    /**
     * While transitioning this property allows to optimize rendering behavior on Android and provide
     * a correct blending options for the animated screen. It is turned on automatically by the
     * container when transitioning is detected and turned off immediately after
     */
    fun setTransitioning(transitioning: Boolean) {
        if (isTransitioning == transitioning) {
            return
        }
        isTransitioning = transitioning
        val isWebViewInScreen = hasWebView(this)
        if (isWebViewInScreen && layerType != LAYER_TYPE_HARDWARE) {
            return
        }
        super.setLayerType(
            if (transitioning && !isWebViewInScreen) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE,
            null,
        )
    }

    private fun hasWebView(viewGroup: ViewGroup): Boolean {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is WebView) {
                return true
            } else if (child is ViewGroup) {
                if (hasWebView(child)) {
                    return true
                }
            }
        }
        return false
    }

    override fun setLayerType(
        layerType: Int,
        paint: Paint?,
    ) {
        // ignore - layer type is controlled by `transitioning` prop
    }

    fun setActivityState(activityState: ActivityState) {
        if (activityState == this.activityState) {
            return
        }
        this.activityState = activityState
        container?.notifyChildUpdate()
    }

    fun setScreenOrientation(screenOrientation: String?) {
        if (screenOrientation == null) {
            this.screenOrientation = null
            return
        }
        ScreenWindowTraits.applyDidSetOrientation()
        this.screenOrientation =
            when (screenOrientation) {
                "all" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                "portrait_up" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "portrait_down" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "landscape_left" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                "landscape_right" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

        fragmentWrapper?.let { ScreenWindowTraits.setOrientation(this, it.tryGetActivity()) }
    }

    // Accepts one of 4 accessibility flags
    // developer.android.com/reference/android/view/View#attr_android:importantForAccessibility
    fun changeAccessibilityMode(mode: Int) {
        this.importantForAccessibility = mode
        this.headerConfig?.toolbar?.importantForAccessibility = mode
    }

    var statusBarStyle: String? = null
        set(statusBarStyle) {
            if (statusBarStyle != null) {
                ScreenWindowTraits.applyDidSetStatusBarAppearance()
            }
            field = statusBarStyle
            fragmentWrapper?.let { ScreenWindowTraits.setStyle(this, it.tryGetActivity(), it.tryGetContext()) }
        }

    var isStatusBarHidden: Boolean? = null
        set(statusBarHidden) {
            if (statusBarHidden != null) {
                ScreenWindowTraits.applyDidSetStatusBarAppearance()
            }
            field = statusBarHidden
            fragmentWrapper?.let { ScreenWindowTraits.setHidden(this, it.tryGetActivity()) }
        }

    var isStatusBarTranslucent: Boolean? = null
        set(statusBarTranslucent) {
            if (statusBarTranslucent != null) {
                ScreenWindowTraits.applyDidSetStatusBarAppearance()
            }
            field = statusBarTranslucent
            fragmentWrapper?.let {
                ScreenWindowTraits.setTranslucent(
                    this,
                    it.tryGetActivity(),
                    it.tryGetContext(),
                )
            }
        }

    var statusBarColor: Int? = null
        set(statusBarColor) {
            if (statusBarColor != null) {
                ScreenWindowTraits.applyDidSetStatusBarAppearance()
            }
            field = statusBarColor
            fragmentWrapper?.let { ScreenWindowTraits.setColor(this, it.tryGetActivity(), it.tryGetContext()) }
        }

    var navigationBarColor: Int? = null
        set(navigationBarColor) {
            if (navigationBarColor != null) {
                ScreenWindowTraits.applyDidSetNavigationBarAppearance()
            }
            field = navigationBarColor
            fragmentWrapper?.let { ScreenWindowTraits.setNavigationBarColor(this, it.tryGetActivity()) }
        }

    var isNavigationBarHidden: Boolean? = null
        set(navigationBarHidden) {
            if (navigationBarHidden != null) {
                ScreenWindowTraits.applyDidSetNavigationBarAppearance()
            }
            field = navigationBarHidden
            fragmentWrapper?.let {
                ScreenWindowTraits.setNavigationBarHidden(
                    this,
                    it.tryGetActivity(),
                )
            }
        }

    var nativeBackButtonDismissalEnabled: Boolean = true

    private fun calculateHeaderHeight(): Pair<Double, Double> {
        val actionBarTv = TypedValue()
        val resolvedActionBarSize = context.theme.resolveAttribute(android.R.attr.actionBarSize, actionBarTv, true)

        // Check if it's possible to get an attribute from theme context and assign a value from it.
        // Otherwise, the default value will be returned.
        val actionBarHeight = TypedValue.complexToDimensionPixelSize(actionBarTv.data, resources.displayMetrics)
            .takeIf { resolvedActionBarSize && headerConfig?.isHeaderHidden != true && headerConfig?.isHeaderTranslucent != true }
            ?.let { PixelUtil.toDIPFromPixel(it.toFloat()).toDouble() } ?: 0.0

        val statusBarHeight = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            // Count only status bar when action bar is visible and status bar is not hidden
            .takeIf { it > 0 && isStatusBarHidden != true && actionBarHeight > 0 }
            ?.let { (context.resources::getDimensionPixelSize)(it) }
            ?.let { PixelUtil.toDIPFromPixel(it.toFloat()).toDouble() }
            ?: 0.0

        return actionBarHeight to statusBarHeight
    }

    private fun notifyHeaderHeightChange(headerHeight: Double) {
        val screenContext = context as ReactContext
        val surfaceId = UIManagerHelper.getSurfaceId(screenContext)
        UIManagerHelper.getEventDispatcherForReactTag(screenContext, id)
            ?.dispatchEvent(HeaderHeightChangeEvent(surfaceId, id, headerHeight))
    }

    internal fun emitOnSheetDetentChanged(detentIndex: Int) {
        val surfaceId = UIManagerHelper.getSurfaceId(reactContext)
        reactEventDispatcher?.dispatchEvent(SheetDetentChangedEvent(surfaceId, id, detentIndex))
    }

    enum class StackPresentation {
        PUSH,
        MODAL,
        TRANSPARENT_MODAL,
        FORM_SHEET,
    }

    enum class StackAnimation {
        DEFAULT,
        NONE,
        FADE,
        SLIDE_FROM_BOTTOM,
        SLIDE_FROM_RIGHT,
        SLIDE_FROM_LEFT,
        FADE_FROM_BOTTOM,
        IOS,
    }

    enum class ReplaceAnimation {
        PUSH,
        POP,
    }

    enum class ActivityState {
        INACTIVE,
        TRANSITIONING_OR_BELOW_TOP,
        ON_TOP,
    }

    enum class WindowTraits {
        ORIENTATION,
        COLOR,
        STYLE,
        TRANSLUCENT,
        HIDDEN,
        ANIMATED,
        NAVIGATION_BAR_COLOR,
        NAVIGATION_BAR_HIDDEN,
    }

    companion object {
        const val TAG = "Screen"

        /**
         * This value describes value in sheet detents array that will be treated as `fitToContents` option.
         */
        const val SHEET_FIT_TO_CONTENTS = -1.0

        /**
         * Verifies whether BottomSheetBehavior.State is one of stable states. As unstable states
         * we consider `STATE_DRAGGING` and `STATE_SETTLING`.
         *
         * @param state bottom sheet state to verify
         */
        fun isStateStable(state: Int): Boolean = when (state) {
            BottomSheetBehavior.STATE_HIDDEN,
            BottomSheetBehavior.STATE_EXPANDED,
            BottomSheetBehavior.STATE_COLLAPSED,
            BottomSheetBehavior.STATE_HALF_EXPANDED -> true

            else -> false
        }

        /**
         * This method maps indices from legal detents array (prop) to appropriate values
         * recognized by BottomSheetBehaviour. In particular used when setting up the initial behaviour
         * of the form sheet.
         *
         * @param index index from array with detents fractions
         * @param detentCount length of array with detents fractions
         *
         * @throws IllegalArgumentException for invalid index / detentCount combinations
         */
        fun sheetStateFromDetentIndex(
            index: Int,
            detentCount: Int,
        ): Int =
            when (detentCount) {
                1 ->
                    when (index) {
                        -1 -> BottomSheetBehavior.STATE_HIDDEN
                        0 -> BottomSheetBehavior.STATE_EXPANDED
                        else -> throw IllegalArgumentException("Invalid detentCount/index combination $detentCount / $index")
                    }
                2 ->
                    when (index) {
                        -1 -> BottomSheetBehavior.STATE_HIDDEN
                        0 -> BottomSheetBehavior.STATE_COLLAPSED
                        1 -> BottomSheetBehavior.STATE_EXPANDED
                        else -> throw IllegalArgumentException("Invalid detentCount/index combination $detentCount / $index")
                    }
                3 ->
                    when (index) {
                        -1 -> BottomSheetBehavior.STATE_HIDDEN
                        0 -> BottomSheetBehavior.STATE_COLLAPSED
                        1 -> BottomSheetBehavior.STATE_HALF_EXPANDED
                        2 -> BottomSheetBehavior.STATE_EXPANDED
                        else -> throw IllegalArgumentException("Invalid detentCount/index combination $detentCount / $index")
                    }
                else -> throw IllegalArgumentException("Invalid detentCount/index combination $detentCount / $index")
            }

        /**
         * This method maps BottomSheetBehavior.State values to appropriate indices of detents array.
         *
         * @param state state of the bottom sheet
         * @param detentCount length of array with detents fractions
         *
         * @throws IllegalArgumentException for invalid state / detentCount combinations
         */
        fun detentIndexFromSheetState(@State state: Int, detentCount: Int): Int {
            return when (detentCount) {
                1 -> when (state) {
                    BottomSheetBehavior.STATE_HIDDEN -> -1
                    BottomSheetBehavior.STATE_EXPANDED -> 0
                    else -> throw IllegalArgumentException("Invalid state $state for detentCount $detentCount")
                }
                2 -> when (state) {
                    BottomSheetBehavior.STATE_HIDDEN -> -1
                    BottomSheetBehavior.STATE_COLLAPSED -> 0
                    BottomSheetBehavior.STATE_EXPANDED -> 1
                    else -> throw IllegalArgumentException("Invalid state $state for detentCount $detentCount")
                }
                3 -> when (state) {
                    BottomSheetBehavior.STATE_HIDDEN -> -1
                    BottomSheetBehavior.STATE_COLLAPSED -> 0
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> 1
                    BottomSheetBehavior.STATE_EXPANDED -> 2
                    else -> throw IllegalArgumentException("Invalid state $state for detentCount $detentCount")
                }
                else -> throw IllegalArgumentException("Invalid state $state for detentCount $detentCount")
            }
        }
    }
}
