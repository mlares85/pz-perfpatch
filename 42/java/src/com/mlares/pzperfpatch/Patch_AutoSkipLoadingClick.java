package com.mlares.pzperfpatch;

import me.zed_0xff.zombie_buddy.annotations.Patch;

import java.lang.reflect.Field;

/**
 * Part of the automated-testing harness: after a save finishes loading, vanilla
 * zombie.gameStates.GameLoadingState.update() (confirmed via jadx decompile + a string search for
 * the "UI_ClickToSkip"/"Click to Start" prompt across the jar's class files) blocks on a real
 * Mouse.isButtonDown(0) or gamepad-A press before handing control to the player -- there is no
 * Lua-side hook for this, it's a raw input poll inside a Java game-state class. An unattended test
 * run has nobody to click, so it would otherwise sit here forever.
 *
 * Sets the same private `forceDone` field the real click sets, via reflection (no public setter
 * exists). Scoped entirely to this one state class -- unlike faking Mouse.isButtonDown(0) globally
 * (which would risk misfiring as a real click everywhere else in the game), this field is only
 * ever read inside GameLoadingState's own update(), so there's no blast radius beyond skipping
 * this one prompt. PZPerfPatch is already an experimental/test-only mod (see its mod.info), so this
 * runs unconditionally rather than behind its own toggle -- there's no meaningful "flip a setting"
 * moment before the very prompt it's meant to skip.
 */
@Patch(className = "zombie.gameStates.GameLoadingState", methodName = "update", warmUp = true)
public class Patch_AutoSkipLoadingClick {
    private static Field forceDoneField;

    @Patch.OnEnter
    public static void enter(@Patch.This Object self) {
        try {
            if (forceDoneField == null) {
                forceDoneField = self.getClass().getDeclaredField("forceDone");
                forceDoneField.setAccessible(true);
            }
            forceDoneField.setBoolean(self, true);
        } catch (ReflectiveOperationException e) {
            System.out.println("[PZPerfPatch] Patch_AutoSkipLoadingClick: reflection failed: " + e);
        }
    }
}
