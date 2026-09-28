/*
 * Copyright 2026 De-Vanced
 * [https://github.com/RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced)
 *
 * Return-refresh hook adapted from Hushfacebook (GPL-3.0).
 */

package app.morphe.patches.facebook.feed.refresh

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.shared.Constants
import app.morphe.patches.facebook.shared.FacebookTargets
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private val extensionPatch = sharedExtensionPatch("facebook", false)

private const val CONTROLLER = "FeedRefreshTriggerController"
private const val ON_REFRESH = "onRefresh"
private const val RETURN_REFRESH =
    "Lapp/morphe/extension/facebook/feed/ReturnRefresh;"
private const val SKIP = "$RETURN_REFRESH->shouldSkip()Z"

@Suppress("unused")
val blockReturnRefreshPatch = bytecodePatch(
    name = "Disable auto refresh",
    description = "Keeps the current feed position when you return to Facebook within ten minutes.",
) {
    compatibleWith(Constants.COMPATIBILITY)
    dependsOn(extensionPatch)

    execute {
        if (packageMetadata.versionName != FacebookTargets.V580) {
            return@execute
        }

        val callbacks = mutableListOf<Method>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/morphe/extension/")) {
                return@classDefForEach
            }
            callbacks += classDef.methods.filter(::isReturnRefreshCallback)
        }
        val callback = callbacks.singleOrNull() ?: error(
            "Expected one FeedRefreshTriggerController resume callback " +
                "holding $ON_REFRESH, found ${callbacks.size}",
        )
        mutableClassDefBy(callback.definingClass).methods.first {
            it.name == callback.name && it.parameterTypes == callback.parameterTypes
        }.skipBriefReturnRefresh()
        println(
            "[DisableAutoRefresh] callback=${callback.definingClass}->${callback.name}",
        )
    }
}

private fun isReturnRefreshCallback(method: Method): Boolean =
    method.returnType == "V" &&
        method.parameterTypes.size == 1 &&
        method.implementation != null &&
        method.holdsString(CONTROLLER) &&
        method.holdsString(ON_REFRESH)

private fun Method.holdsString(string: String): Boolean =
    implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)
            ?.string == string
    } == true

private fun MutableMethod.skipBriefReturnRefresh() {
    val register = getFreeRegisterProvider(0, 1)
        .getFreeRegister4Bit()
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $SKIP
            move-result v$register
            if-eqz v$register, :keep
            return-void
        """.trimIndent(),
        ExternalLabel("keep", getInstruction(0)),
    )
}
