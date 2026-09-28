/*
 * Copyright 2026 De-Vanced
 * [https://github.com/RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced)
 *
 * Startup anchors adapted from Hushfacebook (GPL-3.0).
 */

package app.morphe.patches.facebook.navigation.marketplace

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PATCH = "Open Marketplace on launch"
internal const val TARGET_TAB_ID = "target_tab_id"

internal const val EXTENSION_PACKAGE = "Lapp/morphe/extension/"
internal const val ROUTE =
    "$EXTENSION_PACKAGE" + "navigation/MarketplaceRoute;->onActivityCreate(Landroid/app/Activity;Landroid/os/Bundle;)V"
internal const val INTENT = "Landroid/content/Intent;"
internal const val TAB_TAG = "Lcom/facebook/navigation/tabbar/state/model/TabTag;"
internal const val STARTUP_DESTINATION_ROUTER = "Lcom/facebook/startup/destination/StartupDestinationRouter;"
internal const val START_POSITION = "TabBarController.determineStartingTabPosition"
internal const val MOBILE_CONFIG = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
internal const val SANITIZE_INTENT = "sanitize_intent"
internal const val KEPT_EXTRA = "app_switch_source_account"
internal const val START_ON_ASKED_TAB =
    "$EXTENSION_PACKAGE" + "navigation/MarketplaceRoute;->startOnAskedTab(Z)Z"
internal const val KEEP_ASKED_START_TAB =
    "$EXTENSION_PACKAGE" + "navigation/MarketplaceRoute;->keepAskedStartTab(Z)Z"
internal const val SET_SANITIZED_INTENT =
    "$EXTENSION_PACKAGE" + "navigation/MarketplaceRoute;->setSanitizedIntent(Landroid/app/Activity;Landroid/content/Intent;)V"
internal const val MAIN_TAB_ACTIVITY = "Lcom/facebook/katana/activity/FbMainTabActivity;"

private const val LOOKUP_WINDOW = 8
private const val HAND_OVER_WINDOW = 8
private const val COPY_WINDOW = 24
private const val ACTIVITY = "Landroid/app/Activity;"

private val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.parameters() = parameterTypes.map { it.toString() }

internal fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Method.calls(definingClass: String, name: String, parameters: List<String>, returnType: String) =
    implementation?.instructions?.any { instruction ->
        val call = instruction.call ?: return@any false
        call.definingClass == definingClass && call.name == name && call.returnType == returnType &&
            call.parameters() == parameters
    } == true

private fun readsOwnLong(method: Method) = method.implementation?.instructions?.any {
    it.opcode == Opcode.IGET_WIDE && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
        field.definingClass == method.definingClass && field.type == "J"
    }
} == true

internal fun holdsString(method: Method, string: String): Boolean =
    method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

internal fun picksStartTab(method: Method): Boolean =
    !method.definingClass.startsWith(EXTENSION_PACKAGE) &&
        method.returnType == "J" &&
        method.parameterTypes.any { it.toString() == INTENT } &&
        holdsString(method, TARGET_TAB_ID) &&
        method.calls(INTENT, "hasExtra", listOf("Ljava/lang/String;"), "Z") &&
        method.calls(INTENT, "getLongExtra", listOf("Ljava/lang/String;", "J"), "J")

private fun readsMobileConfigBoolean(instruction: Instruction): Boolean {
    if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val call = instruction.call ?: return false
    return call.definingClass == MOBILE_CONFIG && call.returnType == "Z" &&
        call.parameters() == listOf("Ljava/lang/Object;", "J")
}

internal fun startPositionGate(method: Method): Int? {
    if (method.returnType != "V" || method.parameterTypes.map { it.toString() } != listOf("Z")) return null
    if (!holdsString(method, START_POSITION)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val call = code.indexOfFirst(::readsMobileConfigBoolean)
    if (call < 0) return null
    val result = code.getOrNull(call + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) return null
    val answer = (result as OneRegisterInstruction).registerA
    val branch = code.getOrNull(call + 2)
    if (branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != answer) return null
    val read = code.getOrNull(call + 3)
    if (read?.opcode != Opcode.IGET_WIDE) return null
    val field = (read as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != method.definingClass || field.type != "J") return null
    val startTab = (read as TwoRegisterInstruction).registerA
    val lookup = code.subList(call + 4, minOf(code.size, call + 4 + LOOKUP_WINDOW)).firstOrNull {
        val target = it.call
        target != null && target.parameters() == listOf("J") && target.returnType == "Ljava/lang/Integer;"
    } ?: return null
    val receiver = if (lookup.opcode == Opcode.INVOKE_STATIC || lookup.opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
    if (lookup.callRegisters().getOrNull(receiver) != startTab) return null
    return call + 1
}

internal fun keepsAskedStartTab(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
        method.parameterTypes.map { it.toString() } == listOf(MAIN_TAB_ACTIVITY, method.definingClass) &&
        holdsString(method, TARGET_TAB_ID) &&
        method.calls(INTENT, "getLongExtra", listOf("Ljava/lang/String;", "J"), "J") &&
        readsOwnLong(method)

internal fun sanitizedIntentHandOver(method: Method): Int? {
    if (!holdsString(method, SANITIZE_INTENT)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val kept = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == KEPT_EXTRA }
    if (kept < 0) return null
    val set = (kept + 1 until minOf(code.size, kept + 1 + HAND_OVER_WINDOW)).firstOrNull { index ->
        val call = code[index].call
        (code[index].opcode == Opcode.INVOKE_VIRTUAL || code[index].opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            call != null && call.definingClass == ACTIVITY && call.name == "setIntent" &&
            call.parameters() == listOf(INTENT) && call.returnType == "V"
    } ?: return null
    val copy = code[set].callRegisters().getOrNull(1) ?: return null
    val built = (maxOf(0, kept - COPY_WINDOW) until kept).any { index ->
        val call = code[index].call
        code[index].opcode == Opcode.INVOKE_DIRECT && call != null && call.definingClass == INTENT &&
            call.name == "<init>" && call.parameters() == listOf("Ljava/lang/String;", "Landroid/net/Uri;") &&
            code[index].callRegisters().firstOrNull() == copy
    }
    return if (built) set else null
}
