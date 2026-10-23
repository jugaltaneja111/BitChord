@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/** Whatever is on screen on top, for presenting a system sheet over it. */
internal fun topViewController(): UIViewController? {
    var top: UIViewController? = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}

/**
 * The system share sheet with [items], over whatever is on top. On an iPad it is a popover, which
 * UIKit refuses (with a crash) unless told where it points from: here, the middle of the screen
 * with no arrow, as a sheet would sit.
 */
internal fun presentShareSheet(items: List<Any>) {
    val top = topViewController() ?: return
    val controller = UIActivityViewController(activityItems = items, applicationActivities = null)
    controller.popoverPresentationController?.let { popover ->
        val view = top.view
        popover.setSourceView(view)
        view.bounds.useContents { popover.setSourceRect(CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0)) }
        popover.setPermittedArrowDirections(0uL)
    }
    top.presentViewController(controller, animated = true, completion = null)
}
