/*
 * Copyright 2026 De-Vanced
 * [https://github.com/RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced)
 *
 * Startup route adapted from Hushfacebook (GPL-3.0).
 */

package app.morphe.patches.facebook.navigation.marketplace

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.shared.Constants
import app.morphe.patches.facebook.shared.FacebookTargets
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private val extensionPatch = sharedExtensionPatch("facebook", false)

private const val MARKETPLACE_ROUTE =
    "Lapp/morphe/extension/facebook/settings/DeVancedSettings;"
private const val MARKETPLACE_ON_CREATE =
    "$MARKETPLACE_ROUTE->onMarketplaceActivityCreateV2(Landroid/app/Activity;Landroid/os/Bundle;)V"
private const val MARKETPLACE_START_ON_ASKED_TAB =
    "$MARKETPLACE_ROUTE->startMarketplaceOnAskedTab(Z)Z"
private const val MARKETPLACE_KEEP_ASKED_START_TAB =
    "$MARKETPLACE_ROUTE->keepMarketplaceAskedStartTab(Z)Z"
private const val MARKETPLACE_SET_SANITIZED_INTENT =
    "$MARKETPLACE_ROUTE->setMarketplaceSanitizedIntent(Landroid/app/Activity;Landroid/content/Intent;)V"

@Suppress("unused")
val openMarketplaceOnLaunchPatch = bytecodePatch(
    name = PATCH,
    description = "Opens Marketplace when Facebook is started from its launcher icon.",
) {
    compatibleWith(Constants.COMPATIBILITY)
    dependsOn(extensionPatch)

    execute {
        if (packageMetadata.versionName != FacebookTargets.V580) return@execute

        val pickers = mutableListOf<Method>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
            pickers += classDef.methods.filter(::picksStartTab)
        }
        check(pickers.size == 1) {
            "$PATCH: expected one start-tab picker, found ${pickers.size}"
        }

        val handOver = mutableListOf<Pair<Method, Int>>()
        val gates = mutableListOf<Pair<Method, Int>>()
        val keeps = mutableListOf<Method>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
            handOver += classDef.methods.mapNotNull { method ->
                sanitizedIntentHandOver(method)?.let { method to it }
            }
            gates += classDef.methods.mapNotNull { method ->
                startPositionGate(method)?.let { method to it }
            }
            keeps += classDef.methods.filter(::keepsAskedStartTab)
        }
        check(handOver.size == 1) {
            "$PATCH: expected one sanitized-intent hand-over, found ${handOver.size}"
        }
        check(gates.size == 1) {
            "$PATCH: expected one tab-bar start gate, found ${gates.size}"
        }
        check(keeps.size == 1) {
            "$PATCH: expected one main-screen keep check, found ${keeps.size}"
        }

        val handMethod = mutable(handOver.single().first)
        println(
            "[OpenMarketplaceOnLaunch] handOver=${handOver.single().first.definingClass}->${handOver.single().first.name} " +
                "gate=${gates.single().first.definingClass}->${gates.single().first.name} " +
                "keep=${keeps.single().definingClass}->${keeps.single().name}"
        )
        handMethod.handSanitizedIntentToExtension(handOver.single().second)
        val gate = gates.single()
        mutable(gate.first).askExtensionAfterGate(gate.second)
        mutable(keeps.single()).askExtensionAtReturns()
        declaredInHierarchy(MAIN_TAB_ACTIVITY, "onCreate", "Landroid/os/Bundle;")
            .addInstruction(0, "invoke-static/range {p0 .. p1}, $MARKETPLACE_ON_CREATE")
        println("[OpenMarketplaceOnLaunch] picker=${pickers.single().definingClass}->${pickers.single().name}")
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

private fun BytecodePatchContext.declaredInHierarchy(
    type: String,
    name: String,
    vararg parameters: String,
): MutableMethod {
    var current: ClassDef? = classDefByOrNull(type)
    while (current != null) {
        val method = mutableClassDefByOrNull(current.type)?.methods?.singleOrNull {
            it.name == name && it.returnType == "V" && it.implementation != null &&
                it.parameterTypes.map { value -> value.toString() } == parameters.toList()
        }
        if (method != null) return method
        current = current.superclass?.let { classDefByOrNull(it) }
    }
    error("No class of $type's hierarchy declares $name(${parameters.joinToString("")})V")
}

private fun MutableMethod.handSanitizedIntentToExtension(index: Int) {
    val call = implementation!!.instructions[index]
    val replacement = if (call is RegisterRangeInstruction) {
        "invoke-static/range {v${call.startRegister} .. v${call.startRegister + 1}}, $MARKETPLACE_SET_SANITIZED_INTENT"
    } else {
        val (screen, copy) = call.callRegisters()
        "invoke-static {v$screen, v$copy}, $MARKETPLACE_SET_SANITIZED_INTENT"
    }
    replaceInstruction(index, replacement)
}

private fun MutableMethod.askExtensionAfterGate(index: Int) {
    val branch = implementation!!.instructions[index + 1] as BuilderInstruction
    if (branch.location.labels.isNotEmpty()) {
        throw PatchException("$PATCH: $definingClass->$name has a jump to its start gate.")
    }
    val register = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
    addInstructions(
        index + 1,
        """
            invoke-static/range {v$register .. v$register}, $MARKETPLACE_START_ON_ASKED_TAB
            move-result v$register
        """.trimIndent(),
    )
}

private fun MutableMethod.askExtensionAtReturns() {
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    check(returns.isNotEmpty()) { "$PATCH: $definingClass->$name returns no answer." }
    returns.asReversed().forEach { (index, register) ->
        replaceInstruction(index, "invoke-static/range {v$register .. v$register}, $MARKETPLACE_KEEP_ASKED_START_TAB")
        addInstruction(index + 1, "move-result v$register")
        addInstruction(index + 2, "return v$register")
    }
}
