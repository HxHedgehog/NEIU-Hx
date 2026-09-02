package codechicken.nei;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import codechicken.nei.util.NEIKeyboardUtils;
import codechicken.nei.util.NEIMouseUtils;
import cpw.mods.fml.client.registry.ClientRegistry;

/**
 * Registers NEI key bindings as vanilla {@link KeyBinding}s and exposes their state to the rest of NEI.
 */
public class KeyManager {

    public static interface IKeyStateTracker {

        public void tickKeyStates();
    }

    public static LinkedList<IKeyStateTracker> trackers = new LinkedList<>();
    private static final Map<String, KeyBinding> keyBindings = new java.util.LinkedHashMap<>();
    private static final Map<String, String> keyAliases = new HashMap<>();
    private static volatile boolean pendingOptionsReload = false;

    private static final int MOD_MASK = NEIKeyboardUtils.CTRL_HASH | NEIKeyboardUtils.SHIFT_HASH
            | NEIKeyboardUtils.ALT_HASH;

    private static final String NEI_KEY_PREFIX = "nei.options.keys.";

    /**
     * The 32 vanilla 1.7.10 key binding descriptions (movement/gameplay/inventory/multiplayer/misc/stream plus the 9
     * hotbar slots). Every other binding in gameSettings.keyBindings is registered by a mod (NEI included).
     */
    private static final Set<String> VANILLA_KEY_DESCRIPTIONS = new HashSet<>(
            Arrays.asList(
                    "key.forward",
                    "key.left",
                    "key.back",
                    "key.right",
                    "key.jump",
                    "key.sneak",
                    "key.inventory",
                    "key.use",
                    "key.drop",
                    "key.attack",
                    "key.pickItem",
                    "key.sprint",
                    "key.chat",
                    "key.playerlist",
                    "key.command",
                    "key.screenshot",
                    "key.togglePerspective",
                    "key.smoothCamera",
                    "key.fullscreen",
                    "key.streamStartStop",
                    "key.streamPauseUnpause",
                    "key.streamCommercial",
                    "key.streamToggleMic",
                    "key.hotbar.1",
                    "key.hotbar.2",
                    "key.hotbar.3",
                    "key.hotbar.4",
                    "key.hotbar.5",
                    "key.hotbar.6",
                    "key.hotbar.7",
                    "key.hotbar.8",
                    "key.hotbar.9"));

    /** Keys that must always be a modifier combo (强制组合键): the modifier button never cycles to None. */
    private static final Map<String, Integer> requiredModifiers = new HashMap<>();
    /** Keys that only work as a plain single key (强制单键): no modifier button is shown. */
    private static final Set<String> plainOnlyKeys = new HashSet<>(
            Arrays.asList("bookmark.add", "bookmark.remove_recipe", "bookmark.pull_items"));
    /** Keys that fire only while the search field is focused (搜索框聚焦). */
    private static final Set<String> searchContextKeys = new HashSet<>(
            Arrays.asList("gui.search", "gui.getprevioussearch", "gui.getnextsearch"));
    /** Keys that fire only from the recipe view screen (合成表界面). */
    private static final Set<String> recipeContextKeys = new HashSet<>(
            Arrays.asList(
                    "recipe.back",
                    "recipe.prev_machine",
                    "recipe.next_machine",
                    "recipe.prev_recipe",
                    "recipe.next_recipe"));
    /** Effective default key codes (with modifier bits) used by {@link #resetAllToDefaults()}. */
    private static final Map<String, Integer> defaultKeyCodes = new HashMap<>();

    /**
     * The UI context a key fires in. Keys in different contexts are never considered conflicting, because those
     * contexts are mutually exclusive: the search field is focused only in the NEI overlay, the recipe view screen is a
     * separate screen, and world.* keys fire only when no GUI is open.
     */
    public enum KeyContext {
        WORLD, // world.* keys: fire only with no GUI open
        SEARCH, // search-field keys: fire only while the search box is focused
        RECIPE, // recipe-view keys: fire only from the GuiRecipe screen
        GUI // all other NEI keys: fire from the general NEI overlay
    }

    /** The context a NEI key ident fires in; {@code null} for non-NEI (vanilla/mod) descriptions. */
    public static KeyContext getKeyContext(String ident) {
        if (ident.startsWith("world.")) return KeyContext.WORLD;
        if (searchContextKeys.contains(ident)) return KeyContext.SEARCH;
        if (recipeContextKeys.contains(ident)) return KeyContext.RECIPE;
        return KeyContext.GUI;
    }

    /** The context of a key binding's description; {@code null} for non-NEI descriptions (treated as world context). */
    public static KeyContext getKeyContextByDescription(String description) {
        return description != null && description.startsWith(NEI_KEY_PREFIX)
                ? getKeyContext(description.substring(NEI_KEY_PREFIX.length()))
                : null;
    }

    static {
        // Aliases for legacy keybinds
        keyAliases.put("gui.recipe", "recipe.recipe");
        keyAliases.put("gui.usage", "recipe.usage");
        keyAliases.put("gui.hide_bookmarks", "bookmark.hide");

        requiredModifiers.put("copy.name", NEIKeyboardUtils.CTRL_HASH);
        requiredModifiers.put("copy.identifier", NEIKeyboardUtils.CTRL_HASH);
        requiredModifiers.put("copy.oredict", NEIKeyboardUtils.CTRL_HASH);
        requiredModifiers.put("gui.search", NEIKeyboardUtils.CTRL_HASH);
        requiredModifiers.put("bookmark.chat_link", NEIKeyboardUtils.CTRL_HASH);
        requiredModifiers.put("bookmark.favorite", NEIKeyboardUtils.SHIFT_HASH);
        requiredModifiers.put("bookmark.favorite_item", NEIKeyboardUtils.SHIFT_HASH);
        requiredModifiers.put("itemzoom.toggle", NEIKeyboardUtils.SHIFT_HASH);
        requiredModifiers.put("gui.craft_items", NEIKeyboardUtils.SHIFT_HASH);
    }

    public static KeyBinding registerKeyBinding(String ident, int defaultKey) {
        return keyBindings.computeIfAbsent(ident, id -> {
            int defMod = defaultKey & MOD_MASK;
            if (defMod == 0) defMod = requiredModifiers.getOrDefault(id, 0);
            final int key = NEIKeyboardUtils.unhash(defaultKey);
            final int defaultCode = defMod != 0 ? defMod | key : key;
            defaultKeyCodes.put(id, defaultCode);
            final KeyBinding binding = new KeyBinding("nei.options.keys." + id, defaultCode, getCategory(id));
            ClientRegistry.registerKeyBinding(binding);
            return binding;
        });
    }

    public static KeyBinding getKeyBinding(String ident) {
        return keyBindings.get(keyAliases.getOrDefault(ident, ident));
    }

    /**
     * Returns the identifiers of all registered NEI key bindings in registration order.
     */
    public static List<String> getRegisteredKeyNames() {
        return new ArrayList<>(keyBindings.keySet());
    }

    public static boolean isRegistered(String description) {
        for (KeyBinding binding : keyBindings.values()) {
            if (binding.getKeyDescription().equals(description)) {
                return true;
            }
        }
        return false;
    }

    public static int getKeyCode(String ident) {
        final KeyBinding binding = getKeyBinding(ident);
        return binding == null ? Keyboard.KEY_NONE : binding.getKeyCode();
    }

    /** The stored modifier mask (CTRL/SHIFT/ALT_HASH) of a key, 0 if none. */
    public static int getModifierMask(String ident) {
        return getKeyCode(ident) & MOD_MASK;
    }

    /** The stored main key of a key (modifier bits stripped). */
    public static int getMainKeyCode(String ident) {
        return NEIKeyboardUtils.unhash(getKeyCode(ident));
    }

    /** Whether this key must always be a modifier combo (强制组合键). */
    public static boolean isRequiredModifierKey(String ident) {
        return requiredModifiers.containsKey(ident);
    }

    /** Whether this key only works as a plain single key (强制单键). */
    public static boolean isPlainOnlyKey(String ident) {
        return plainOnlyKeys.contains(ident);
    }

    /** The default modifier for a required-combo key, 0 otherwise. */
    public static int getDefaultModifier(String ident) {
        return requiredModifiers.getOrDefault(ident, 0);
    }

    /** Stores a new main key for the given binding, preserving its stored modifier. */
    public static void setMainKey(String ident, int mainKey) {
        final KeyBinding binding = getKeyBinding(ident);
        if (binding == null) return;
        binding.setKeyCode((getKeyCode(ident) & MOD_MASK) | NEIKeyboardUtils.unhash(mainKey));
    }

    /** Stores a new modifier mask for the given binding, preserving its main key. */
    public static void setModifier(String ident, int modifierMask) {
        final KeyBinding binding = getKeyBinding(ident);
        if (binding == null) return;
        binding.setKeyCode((getKeyCode(ident) & ~MOD_MASK) | (modifierMask & MOD_MASK));
    }

    public static boolean isKeyDown(String ident) {
        final KeyBinding binding = getKeyBinding(ident);

        if (binding == null) {
            return false;
        }

        final int keyCode = binding.getKeyCode();
        final int mainKey;
        final int mask;
        if (keyCode < 0) {
            // mouse button binding (no modifier combo)
            mainKey = keyCode;
            mask = 0;
        } else {
            mask = keyCode & MOD_MASK;
            mainKey = mask == 0 ? keyCode : NEIKeyboardUtils.unhash(keyCode);
        }

        if (mainKey < 0) {
            if (!Mouse.isButtonDown(mainKey + 100)) return false;
        } else if (mainKey > Keyboard.KEY_NONE && mainKey < Keyboard.KEYBOARD_SIZE) {
            if (!Keyboard.isKeyDown(mainKey)) return false;
        } else {
            return false;
        }

        // main key bound directly to a modifier key: any modifier state matches
        if (NEIKeyboardUtils.isHashKey(mainKey)) {
            return true;
        }

        // the stored modifier combo must currently be held
        return ((mask & NEIKeyboardUtils.CTRL_HASH) != 0) == NEIClientUtils.controlKey()
                && ((mask & NEIKeyboardUtils.SHIFT_HASH) != 0) == NEIClientUtils.shiftKey()
                && ((mask & NEIKeyboardUtils.ALT_HASH) != 0) == NEIClientUtils.altKey();
    }

    public static String getKeyName(String ident) {
        return getKeyName(ident, 0);
    }

    /**
     * Every currently-conflicting binding (NEI keys + third-party mod keys).
     */
    public static List<KeyBinding> getConflictingKeyBindings() {
        List<KeyBinding> list = new ArrayList<>();
        for (String ident : getRegisteredKeyNames()) {
            if (isKeyConflicting(ident)) list.add(getKeyBinding(ident));
        }
        for (KeyBinding binding : getOtherModKeyBindings()) {
            if (isKeyBindingConflicting(binding)) list.add(binding);
        }
        return list;
    }

    /**
     * Vanilla bindings involved in any current conflict: colliding with a NEI/mod key, or with another vanilla binding.
     * Shown inside the NEI "冲突键位" screen so the user can see (and rebind) the vanilla side of a conflict; the vanilla
     * "controls" screen keeps them untouched.
     */
    public static List<KeyBinding> getConflictingVanillaBindings() {
        List<KeyBinding> list = new ArrayList<>();
        for (KeyBinding vb : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (vb != null && isVanillaKeyByDescription(vb.getKeyDescription()) && isKeyBindingConflicting(vb)) {
                list.add(vb);
            }
        }
        return list;
    }

    /**
     * Whether a key description belongs to a vanilla 1.7.10 key binding.
     */
    public static boolean isVanillaKeyByDescription(String description) {
        return description != null && VANILLA_KEY_DESCRIPTIONS.contains(description);
    }

    /** Whether a key binding is a non-vanilla (mod) binding, NEI keys included. */
    public static boolean isModKey(KeyBinding binding) {
        return binding != null && !isVanillaKeyByDescription(binding.getKeyDescription());
    }

    /** Whether a description belongs to a NEI-owned key binding. */
    public static boolean isNeiKeyByDescription(String description) {
        return description != null && description.startsWith(NEI_KEY_PREFIX);
    }

    /** Whether a description belongs to a third-party mod key binding (neither vanilla nor NEI). */
    public static boolean isOtherModKeyByDescription(String description) {
        return description != null && !isVanillaKeyByDescription(description) && !isNeiKeyByDescription(description);
    }

    /** All non-vanilla key bindings currently registered (NEI keys + other mods' keys). */
    public static List<KeyBinding> getModKeyBindings() {
        List<KeyBinding> list = new ArrayList<>();
        for (KeyBinding binding : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (isModKey(binding)) list.add(binding);
        }
        return list;
    }

    /** All third-party (non-NEI) mod key bindings currently registered. */
    public static List<KeyBinding> getOtherModKeyBindings() {
        List<KeyBinding> list = new ArrayList<>();
        for (KeyBinding binding : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (isOtherModKeyByDescription(binding.getKeyDescription())) list.add(binding);
        }
        return list;
    }

    /**
     * Whether a key binding conflicts with any other binding sharing the same full key code. Vanilla bindings are
     * world-context, so they collide with third-party mod keys, other vanilla keys, and world-context NEI keys, but not
     * with GUI/search/recipe-context NEI keys. Third-party mod keys fire in any context and conflict with everything
     * sharing the code.
     */
    public static boolean isKeyBindingConflicting(KeyBinding binding) {
        if (binding == null) return false;
        final int code = binding.getKeyCode();
        if (code == Keyboard.KEY_NONE) return false;
        if (isVanillaKeyByDescription(binding.getKeyDescription())) {
            for (KeyBinding other : Minecraft.getMinecraft().gameSettings.keyBindings) {
                if (other == null || other == binding || other.getKeyCode() != code) continue;
                if (isOtherModKeyByDescription(other.getKeyDescription())) return true;
                if (!isNeiKeyByDescription(other.getKeyDescription())) return true; // another vanilla key
                if (getKeyContextByDescription(other.getKeyDescription()) == KeyContext.WORLD) return true;
            }
            return false;
        }
        for (KeyBinding other : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (other != null && other != binding && other.getKeyCode() == code) return true;
        }
        return false;
    }

    /**
     * Whether this key conflicts with another key binding (vanilla or mod). The FULL stored key code (including the NEI
     * modifier bits) is compared, so a combo key like Ctrl+C never conflicts with a plain C key, nor with a combo of
     * the same main key under a different modifier. An unbound binding never conflicts, even if stale modifier bits
     * remain in its stored code. Two NEI keys conflict only when they share the same {@link KeyContext} (e.g. a
     * search-field key never conflicts with a recipe-view key, since those screens are mutually exclusive); vanilla
     * bindings are treated as world-context. Third-party mod keys fire in any context and conflict with everything
     * sharing the code.
     */
    public static boolean isKeyConflicting(String ident) {
        final KeyBinding binding = getKeyBinding(ident);
        if (binding == null) return false;
        if (getMainKeyCode(ident) == Keyboard.KEY_NONE) return false;
        final int code = binding.getKeyCode();
        final KeyContext ctx = getKeyContext(ident);
        for (KeyBinding other : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (other == null || other == binding || other.getKeyCode() != code) continue;
            if (isOtherModKeyByDescription(other.getKeyDescription())) return true;
            final KeyContext otherCtx = getKeyContextByDescription(other.getKeyDescription());
            // Vanilla bindings are world-context; only a world-context NEI key can collide with them.
            if (otherCtx == null) {
                if (ctx == KeyContext.WORLD) return true;
                continue;
            }
            if (ctx == otherCtx) return true;
        }
        return false;
    }

    public static String getKeyName(String ident, int meta) {
        final int keyCode = getKeyCode(ident);
        return keyCode == Keyboard.KEY_NONE ? null : NEIKeyboardUtils.getKeyName(keyCode + meta);
    }

    public static String getKeyName(String ident, int meta, int mouseBind) {
        final int keyCode = getKeyCode(ident);
        return keyCode == Keyboard.KEY_NONE && mouseBind == NEIMouseUtils.MOUSE_BTN_NONE ? null
                : getKeyName(keyCode + meta, mouseBind);
    }

    public static String getKeyName(int keyBind, int mouseBind) {
        StringJoiner keyText = new StringJoiner(" + ");
        String keyHash = keyBind == Keyboard.KEY_NONE ? "" : NEIKeyboardUtils.getKeyName(keyBind);
        String mouseHash = mouseBind == NEIMouseUtils.MOUSE_BTN_NONE ? "" : NEIMouseUtils.getKeyName(mouseBind);

        if (!keyHash.isEmpty()) {
            keyText.add(keyHash);
        }

        if (!mouseHash.isEmpty()) {
            keyText.add(mouseHash);
        }

        return keyText.toString();
    }

    public static boolean isPressed(String ident) {
        final KeyBinding binding = getKeyBinding(ident);
        return binding != null && binding.isPressed();
    }

    /** The category lang key (e.g. "nei.options.keys.gui") for a key ident. */
    public static String getCategory(String ident) {
        // 第三方 mod (Waila) 注册的 "showenchant" 键没有前缀，单独归入 "Waila联动" 分类
        if (ident.equals("showenchant")) return "nei.options.keys.waila_linkup";
        final int dot = ident.indexOf('.');
        return dot < 0 ? "nei.options.keys" : "nei.options.keys." + ident.substring(0, dot);
    }

    public static void requestOptionsReload() {
        pendingOptionsReload = true;
    }

    /** Resets every registered NEI key binding back to its default key (with default modifier bits). */
    public static void resetAllToDefaults() {
        for (Map.Entry<String, Integer> entry : defaultKeyCodes.entrySet()) {
            final KeyBinding binding = keyBindings.get(entry.getKey());
            if (binding != null) binding.setKeyCode(entry.getValue());
        }
    }

    /**
     * Resets every key binding editable from the NEI 键位设置 screen (NEI keys, third-party mod keys and vanilla keys) back
     * to its default key.
     */
    public static void resetAllKeysToDefaults() {
        resetAllToDefaults();
        for (KeyBinding binding : getOtherModKeyBindings()) {
            binding.setKeyCode(binding.getKeyCodeDefault());
        }
        for (KeyBinding binding : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (binding != null && isVanillaKeyByDescription(binding.getKeyDescription())) {
                binding.setKeyCode(binding.getKeyCodeDefault());
            }
        }
    }

    public static void tickKeyStates() {

        if (pendingOptionsReload) {
            pendingOptionsReload = false;
            Minecraft.getMinecraft().gameSettings.loadOptions();
            fixRequiredModifiers();
        }

        for (IKeyStateTracker tracker : trackers) tracker.tickKeyStates();
    }

    /**
     * Required-combo keys (强制组合键) can never be None. Older options.txt entries may have stored the plain main key
     * without a modifier; restore the default modifier so the combo requirement survives a configuration migration.
     */
    private static void fixRequiredModifiers() {
        for (Map.Entry<String, KeyBinding> entry : keyBindings.entrySet()) {
            final Integer defMod = requiredModifiers.get(entry.getKey());
            if (defMod == null) continue;
            final KeyBinding binding = entry.getValue();
            final int keyCode = binding.getKeyCode();
            if (keyCode > 0 && (keyCode & MOD_MASK) == 0) {
                binding.setKeyCode(keyCode | defMod);
            }
        }
    }
}
