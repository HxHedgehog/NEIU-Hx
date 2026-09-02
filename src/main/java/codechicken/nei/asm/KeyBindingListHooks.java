package codechicken.nei.asm;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import codechicken.nei.KeyManager;

/**
 * Runtime hooks referenced by the ASM patch that hides every mod-owned key binding (NEI keys and other mods' keys) from
 * the vanilla "controls" screen ({@code GuiKeyBindingList}), while leaving them fully functional. They are managed
 * instead from the NEI "键位设置" screen.
 */
public final class KeyBindingListHooks {

    private KeyBindingListHooks() {}

    /**
     * Returns a new array containing only the given column's vanilla key bindings. Called right after
     * {@code GuiKeyBindingList.<init>} clones {@code gameSettings.keyBindings} into its display array, so both the list
     * capacity and the rendered rows exclude every mod-owned binding.
     */
    public static KeyBinding[] trimModKeys(KeyBinding[] bindings) {
        if (bindings == null || bindings.length == 0) return bindings;
        List<KeyBinding> visible = new ArrayList<>(bindings.length);
        for (KeyBinding binding : bindings) {
            if (KeyManager.isModKey(binding)) continue;
            visible.add(binding);
        }
        return visible.toArray(new KeyBinding[visible.size()]);
    }

    /**
     * Removes any trailing {@code null} slots from {@code GuiKeyBindingList}'s entry array. Because a handful of mod
     * keys are trimmed before the array is allocated, the size still counts the full number of key categories; the
     * unfilled tail entries stay {@code null} and would crash the renderer.
     */
    public static GuiListExtended.IGuiListEntry[] compactEntries(GuiListExtended.IGuiListEntry[] entries) {
        if (entries == null || entries.length == 0) return entries;
        int n = entries.length;
        while (n > 0 && entries[n - 1] == null) n--;
        if (n == entries.length) return entries;
        GuiListExtended.IGuiListEntry[] compact = new GuiListExtended.IGuiListEntry[n];
        System.arraycopy(entries, 0, compact, 0, n);
        return compact;
    }

    /**
     * Combo- and context-aware conflict check wired into the vanilla controls screen (replaces the raw
     * {@code getKeyCode() == getKeyCode()} comparison). Two bindings conflict only when their full stored key codes are
     * identical — NEI modifier bits included — so a combo key like Ctrl+C is never treated as its plain main key, nor
     * as a different combo sharing the same main key. Bindings that fire in different contexts (a search-field NEI key
     * vs a recipe-view NEI key) never collide. Vanilla bindings (no NEI context) are world-context, so they keep the
     * original same-code conflict rule among themselves.
     */
    public static boolean conflicts(KeyBinding a, KeyBinding b) {
        final int ca = a.getKeyCode();
        if (ca == Keyboard.KEY_NONE || ca != b.getKeyCode()) return false;
        return KeyManager.getKeyContextByDescription(a.getKeyDescription())
                == KeyManager.getKeyContextByDescription(b.getKeyDescription());
    }
}
