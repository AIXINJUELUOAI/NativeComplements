# Source attribution

Feature-entry, menu, quick-function and suggestion icons: Google Material Symbols Rounded, filled 24px Android vectors, Apache-2.0.
Source: https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/android
Assets are stored as ic_game_*.xml, ic_qs_*.xml, ic_suggestion_*.xml and ic_barrage_notes.xml. The flashlight and airplane glyphs reuse the Android Open Source Project SystemUI vectors
ic_flashlight_off and ic_statusbar_airplanemode (ic_qs_flashlight.xml, ic_qs_airplane.xml), Apache-2.0.
Source: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/res/drawable/
Theme/state tint is applied by the native ImageView; the fixed white disc and black glyph styling are module UI code.

Geometry.java adapts the mini-window scale constants, 9:16 canvas ratio and opposite-orientation scale factor from WindowResizingAlgorithm.java, Copyright (C) 2023-2024 The Nameless-AOSP Project, SPDX-License-Identifier: Apache-2.0.

Source: https://github.com/SunOS-Project/android_sun-sdk/blob/1f4bd2d7494dc2c317d86d156dcb3fc234a88aae/services/src/com/android/server/wm/WindowResizingAlgorithm.java

Changes: replaced Android geometry dependencies with a pure Java calculation; portrait centered / landscape left placement; safe-area and margin fit. This experimental adapter does not bundle decompiled Google/PixelOS classes or modified framework jars. libxposed API 101.0.1 is compile-only.

Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0

Demo6: GestureMath.java also adapts SunOS WindowResizingAlgorithm / WindowPositioner scale thresholds and state semantics under Apache-2.0. New code separates pure threshold checks from task/overlay handling.

Demo6.2: MotionStyle / WindowControls use the 114dp / 4.8dp / 16dp bar geometry from frameworks/res/values/dimens.xml and MiniWindowEdgeBarHelper.java in the same SunOS revision. WindowEngine adapts the 320ms mode-change duration and PathInterpolator(0.2,0,0,1) from WindowManager/src/com/android/wm/shell/popupview/PopUpViewTransitionAnimationSpec.java. Copyright (C) 2023-2024 The Nameless-AOSP Project, Apache-2.0. The Binder transport, local handle prediction and normalized visual-radius formula are new adapter code. SunOS custom window modes and screenshot-based Shell transitions are not bundled.

Demo6.4: normal/mini morphs use the 320ms mode-change curve cited above; settling retains 336ms / AccelerateDecelerateInterpolator from services/src/com/android/server/wm/PopUpAnimationController.java. The previous demo mistakenly applied the pinned-window 200ms / OvershootInterpolator(1.8) size-toggle parameters to normal/mini mode changes; this is corrected here. GestureMath adds the actual minimum scale .28 and orientation-dependent maximum from WindowPositioner.resizeDrag. The task-relative decoration layer, temporary recents filtering, raw-finger distance adapter, upper-right destination, and direct surface transaction submission are adapter code. SunOS spring-based docking and Shell snapshot crossfades are not reproduced by these parameter changes.

Demo7: BackSwipe uses the 0.5 portrait / 0.28 landscape defaults from frameworks/src/com/android/internal/policy/GestureLongSwipeUtils.java in the pinned SunOS SDK revision (Nameless-AOSP, Apache-2.0). Display-width multiplication and cancellation before invoking the custom action are also confirmed in android_frameworks_base_old commit f9314eb17e9db7d37350e153d2b0993b7501f31b, EdgeBackGestureHandler.java and BackPanelController.kt. BackBridge adds a device-specific adapter around the existing SystemUI BackPanelController; it cancels native back on admitted long-swipe release, then waits for Shell-owned back-navigation state to clear on its executor before submitting the task. The 12dp withdrawal hysteresis, feedback transport, exact-task recents/stub filtering, layout-deferred close order and combined resize-release command are new adapter code. The animation clock prefers Choreographer.getSfInstance, as used by SunOS PopUpAnimationController; this does not reproduce SunOS's full transition framework.

Demo7.1: adds a larger touch-only handle region with exact-region launcher guarding; adjusts landscape default scales and stable/cutout inset fitting; adds configurable single/double-tap outside dismissal, an in-place opacity fade with per-task no-animation restoration, and task-to-back interception for the owned window. Screenshot lifecycle signals yield accessibility touch overlays to system tools and preserve an already immersive background's system-bar request. The app-list card registers a standard OnBackInvokedCallback. Mini content input is excluded through the owned window's canReceiveTouchInput result, without persistently changing application window flags. These are adapter changes, not a direct port of SunOS screenshot or exit animation code.

Demo8: fixes the app-launch retry state after switching windows, separates native background-transition completion from fullscreen configuration restoration, applies no-animation flags after the owned task is actually collected, and resets native crop bookkeeping on restore. A task-identity history persists recents hiding across close/switch until the task is genuinely drawn fullscreen; launcher filtering receives that history directly from system_server. Landscape placement fits the stable safe area. These are original adapter changes based on read-only inspection of the target Android 17 framework, without bundling target decompiled code.

Demo8.1: landscape content and mini/handle touch geometry reserve only the display cutout. Adds a shared centered 94%-to-100% visibility path, sampled in reverse for exit, with content and the native handle submitted together. After custom exit completes, the owned task's dedicated native move-to-back transition is marked TRANSIT_FLAG_INVISIBLE before requesting Shell playback; unrelated/shared transitions retain native behavior. These are original adapter changes, not a direct port of SunOS entry/exit animations.

Demo8.2: adds a preference to hide the side long-swipe hint overlay while retaining threshold haptics and gesture admission; raises the corner hotspot minimum/default to 36dp with a 72dp upper bound. These are frontend settings changes; the Demo8.1 window/transition implementation and protocol14 remain in use.

Demo8.2 additionally makes fan selection distance configurable (50%-100% of the drawn icon arc, default90%), independent of the menu-opening gesture distance. Drawing and selection share the animated/fitted radius.

NativeComplements 0.1.0-preview1: migrates the management home and settings to Compose Material 3 Expressive in a single Gradle app module. The 26 Java runtime/diagnostic classes retain their exact Demo8.2 source bytes and package names. New app-process UI adapters preserve existing preference keys, signature permission, protocol 14 and accessibility component identity. This management UI migration does not port additional SunOS framework behavior.

NativeComplements 0.2.0-preview1: replaces the accessibility host with a SystemUI window-context host and a UID-checked settings bridge. Generic raw-coordinate conversion follows the (raw - displayOffset) / displayScale semantics of SunOS MotionEventExt.java and ViewRootImplExt.java; per-task orientation follows WindowContainerExt.setOrientation, and the app-facing non-split distinction follows SunOS WindowConfiguration.inMultiWindowMode. These references belong to the same Nameless-AOSP / SunOS revision above, Apache-2.0. New LSPosed adapters, read-only per-task Binder queries, host lifecycle, icon normalization and task recovery changes are original module code. This does not import SunOS custom WM modes or claim identical full ROM behavior.

0.2.0-preview2: local icon rasterization, detached-session recovery, system-server viewport configuration; no application-process hooks.

0.2.0-preview3: adopts SunOS WindowState.canAffectSystemUiFlags popup exclusion from android_frameworks_base_old f9314eb17e9db7d37350e153d2b0993b7501f31b (Nameless-AOSP, Apache-2.0). Process transaction copies and logical display rotation/max bounds are original LSPosed adapters, not a full SunOS ROM port. SunOS floating-window inset filtering is preserved; no fabricated status-bar padding.


0.2.0-preview5: restores Demo8.2 native freeform reporting while retaining logical rotation/max bounds and bar-appearance exclusion. Serial source-task handoff follows the source-based launch inheritance principle in SunOS PopUpWindowController.computeBeforeExecuteRequest (SDK revision above); restoration before acquiring the next task is original adapter code, not a port of SunOS multi-task popup lifecycle. TaskDecoration retains MiniWindowEdgeBarHelper fixed #FFF0F2F2 and adds an original dark outline; no automatic background sampling is claimed. No target framework or application decompilation is bundled.

preview5: native root-launcher layout refresh; current-request caller-identity task handoff; fixed light handle without outline. These are module adaptations, not a complete SunOS framework port.

0.2.0-preview6: adds an original temporary task-surface visibility guard with per-handoff cancellation and timeout, and bounded system-side launch/relaunch diagnostics. Existing source/caller inheritance follows the previously cited SunOS principle; this is not the SunOS popup mode or a full transition implementation. Fixed light handle is retained without the old outline.

0.2.0-preview7: original freeform adaptations for explicit expansion focus, visible mini lifecycle eligibility and final transition flags/start-finish surface masking with late-commit recovery. Reviewed SunOS PopUpWindowController focus routing and target Android 17 framework behavior; no SunOS popup-mode transplant or app-process hooks.

Preview20: mini menu vector icons, circle/menu shapes, colors, 42dp buttons and 20dp spacing reuse frameworks/res from SunOS android_sun-sdk commit 1f4bd2d7494dc2c317d86d156dcb3fc234a88aae (The Nameless-AOSP Project, Apache-2.0); resource names are prefixed nc_ and loaded from the module APK. The paired mini lifecycle predicates adapt ActivityRecord.isFocusableOrPopUpView usage in shouldBeResumed and shouldPauseActivity from android_frameworks_base_old commit f9314eb17e9db7d37350e153d2b0993b7501f31b; native task/input focus remains unchanged.

0.7.8: fixed #FFF0F2F2 handle and .4 normal-window background dim amount follow MiniWindowEdgeBarHelper.java and DimmerWindow.java from SunOS android_sun-sdk commit 1f4bd2d7494dc2c317d86d156dcb3fc234a88aae (The Nameless-AOSP Project, Apache-2.0). The below-task SurfaceControl color-layer adapter, shared WM-lock frame guard, and removal of sampled handle contrast are module changes, not a transplant of TYPE_MINI_WINDOW_DIMMER.
