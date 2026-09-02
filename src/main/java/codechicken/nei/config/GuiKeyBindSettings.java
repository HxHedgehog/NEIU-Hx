package codechicken.nei.config;

import static codechicken.lib.gui.GuiDraw.drawMultilineTip;
import static codechicken.lib.gui.GuiDraw.drawStringC;
import static codechicken.lib.gui.GuiDraw.fontRenderer;
import static codechicken.lib.gui.GuiDraw.getMousePosition;
import static codechicken.lib.gui.GuiDraw.getStringWidth;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import codechicken.core.gui.GuiCCButton;
import codechicken.core.gui.GuiScreenWidget;
import codechicken.core.gui.GuiScrollSlot;
import codechicken.nei.KeyManager;
import codechicken.nei.LayoutManager;
import codechicken.nei.NEIClientUtils;
import codechicken.nei.util.NEIKeyboardUtils;

/**
 * A NEI-internal screen for managing every non-vanilla (mod) key binding. It is two-level: first a menu listing the
 * group entries — "冲突键位" (keys currently in conflict), "NEI" (NEI's own keys), one entry per third-party mod with more
 * than 5 keys, and a merged "其他" entry for the smaller mods — then, after clicking an entry, the key list of that group
 * with full editing. Opened from the NEI options list; the vanilla "controls" screen is patched to hide all these mod
 * keys.
 */
public class GuiKeyBindSettings extends GuiScreenWidget {

    private final Option opt;
    private final GuiOptionList parentGui;

    private KeysScrollSlot slot;
    private GuiCCButton backButton;
    private GuiCCButton resetButton;
    private int selectedIndex = -1;
    private boolean listening;

    /** null = menu page; otherwise the id of the group currently shown. */
    private String currentGroup = null;

    private static final String GROUP_CONFLICTS = "conflicts";
    private static final String GROUP_NEI = "nei";

    public GuiKeyBindSettings(Option opt) {
        super(176, 166);
        this.opt = opt;
        this.parentGui = (GuiOptionList) Minecraft.getMinecraft().currentScreen;
    }

    @Override
    public void initGui() {
        xSize = width;
        ySize = height;
        super.initGui();
    }

    @Override
    public void addWidgets() {
        add(slot = new KeysScrollSlot());
        add(
                backButton = new GuiCCButton(0, 0, 0, 20, StatCollector.translateToLocal("nei.options.back"))
                        .setActionCommand("back"));
        add(
                resetButton = new GuiCCButton(0, 2, 0, 16, StatCollector.translateToLocal("nei.options.keys.reset"))
                        .setActionCommand("reset"));
    }

    @Override
    public void resize() {
        slot.resize();
        backButton.width = Math.min(200, width - 40);
        backButton.x = (width - backButton.width) / 2;
        backButton.y = height - 25;
        resetButton.width = 60;
        resetButton.x = width - resetButton.width - 15;
    }

    @Override
    public void actionPerformed(String ident, Object... params) {
        if (ident.equals("back")) {
            // The "关闭设置界面" button closes the entire settings GUI, regardless of the current level
            GuiScreen p = parentGui;
            while (p instanceof GuiOptionList) p = ((GuiOptionList) p).parent;
            Minecraft.getMinecraft().displayGuiScreen(p);
        } else if (ident.equals("reset")) {
            KeyManager.resetAllKeysToDefaults();
            commit();
        }
    }

    @Override
    public void drawBackground() {
        drawDefaultBackground();
    }

    @Override
    public void drawForeground() {
        drawCenteredString(
                fontRenderer,
                currentGroup == null ? StatCollector.translateToLocal("nei.options.nei_keybindings")
                        : slot.groupTitle(currentGroup),
                width / 2,
                6,
                -1);
        drawTooltip();
    }

    private void drawTooltip() {
        List<String> tooltip = new LinkedList<>();
        Point mouse = getMousePosition();
        if (resetButton.pointInside(mouse.x, mouse.y))
            tooltip.add(StatCollector.translateToLocal("nei.options.keys.reset.tip"));
        drawMultilineTip(mouse.x + 12, mouse.y - 12, tooltip);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void save() {
        Minecraft.getMinecraft().gameSettings.saveOptions();
    }

    /** Persists and rebuilds the list (conflict membership can change after any rebind). */
    private void commit() {
        listening = false;
        selectedIndex = -1;
        save();
        slot.rebuild();
    }

    /** Opens a group page (or the menu when group is null). */
    private void enterGroup(String group) {
        currentGroup = group;
        listening = false;
        selectedIndex = -1;
        slot.rebuild();
    }

    @Override
    public void keyTyped(char c, int keycode) {
        if (listening && selectedIndex >= 0) {
            KeysScrollSlot.Entry e = slot.entryAt(selectedIndex);
            if (keycode == Keyboard.KEY_ESCAPE || keycode == Keyboard.KEY_BACK) {
                listening = false;
            } else if (keycode != Keyboard.KEY_NONE && e != null) {
                // 主键不允许为修饰键：忽略 Ctrl/Shift/Alt 及其左右变体，保持监听
                if (NEIKeyboardUtils.isHashKey(keycode)) return;
                if (e.kind == KeysScrollSlot.Entry.Kind.MOD_KEY) {
                    e.binding.setKeyCode(keycode);
                } else {
                    KeyManager.setMainKey(e.ident, keycode);
                }
                commit();
            }
            return;
        }

        if (keycode == Keyboard.KEY_ESCAPE || keycode == Keyboard.KEY_BACK) {
            // 键位页按 Esc 先回到子菜单，再按一次回到 NEI 选项
            if (currentGroup != null) {
                enterGroup(null);
            } else {
                Minecraft.getMinecraft().displayGuiScreen(parentGui);
            }
            return;
        }

        super.keyTyped(c, keycode);
    }

    private class KeysScrollSlot extends GuiScrollSlot {

        private static final int[] REQUIRED_MOD_CYCLE = { NEIKeyboardUtils.CTRL_HASH, NEIKeyboardUtils.SHIFT_HASH,
                NEIKeyboardUtils.ALT_HASH };
        private static final int[] FREE_MOD_CYCLE = { 0, NEIKeyboardUtils.CTRL_HASH, NEIKeyboardUtils.SHIFT_HASH,
                NEIKeyboardUtils.ALT_HASH };

        /** Flat entry list: menu entries, or section headers interleaved with key rows. */
        private final List<Entry> entries = new ArrayList<>();

        private static final class Entry {

            enum Kind {
                HEADER,
                MENU_ITEM,
                NEI_KEY,
                MOD_KEY
            }

            final Kind kind;
            final String title; // HEADER/MENU_ITEM: localized title
            final String groupId; // MENU_ITEM: group to open
            final String ident; // NEI_KEY
            final KeyBinding binding; // MOD_KEY

            Entry(Kind kind, String title, String groupId, String ident, KeyBinding binding) {
                this.kind = kind;
                this.title = title;
                this.groupId = groupId;
                this.ident = ident;
                this.binding = binding;
            }
        }

        KeysScrollSlot() {
            super(0, 0, 0, 0);
            setMargins(0, 4, 20, 4);
            rebuild();
        }

        /** All third-party mod bindings grouped by their key category, in first-seen order. */
        private Map<String, List<KeyBinding>> modBindingsByCategory() {
            Map<String, List<KeyBinding>> map = new LinkedHashMap<>();
            for (KeyBinding binding : KeyManager.getOtherModKeyBindings()) {
                map.computeIfAbsent(binding.getKeyCategory(), cat -> new ArrayList<>()).add(binding);
            }
            return map;
        }

        private void rebuild() {
            entries.clear();
            if (currentGroup == null) {
                buildMenu();
            } else {
                buildGroup(currentGroup);
            }
        }

        /** The localized title shown at the top for a group page. */
        String groupTitle(String group) {
            if (group.equals(GROUP_CONFLICTS)) return StatCollector.translateToLocal("nei.options.keys.conflicts");
            if (group.equals(GROUP_NEI)) return StatCollector.translateToLocal("nei.options.keys.nei");
            return StatCollector.translateToLocal(group);
        }

        /**
         * Menu page: clickable entries (冲突键位/NEI/大模组) on top, then the smaller mods' keys listed directly below, each
         * under its own centered header.
         */
        private void buildMenu() {
            // 1) 冲突键位入口 (a filter view; only present while a conflict exists)
            if (!KeyManager.getConflictingKeyBindings().isEmpty()
                    || !KeyManager.getConflictingVanillaBindings().isEmpty()) {
                entries.add(
                        new Entry(
                                Entry.Kind.MENU_ITEM,
                                StatCollector.translateToLocal("nei.options.keys.conflicts"),
                                GROUP_CONFLICTS,
                                null,
                                null));
            }

            // 2) NEI 入口
            entries.add(
                    new Entry(
                            Entry.Kind.MENU_ITEM,
                            StatCollector.translateToLocal("nei.options.keys.nei"),
                            GROUP_NEI,
                            null,
                            null));

            // 3) Third-party mods: mods with more than 5 keys get their own clickable entry; the keys
            // of smaller mods are listed directly below the menu instead of behind a merged entry.
            Map<String, List<KeyBinding>> byCategory = modBindingsByCategory();
            for (Map.Entry<String, List<KeyBinding>> catEntry : byCategory.entrySet()) {
                if (catEntry.getValue().size() > 5) {
                    entries.add(
                            new Entry(
                                    Entry.Kind.MENU_ITEM,
                                    StatCollector.translateToLocal(catEntry.getKey()),
                                    catEntry.getKey(),
                                    null,
                                    null));
                }
            }
            for (Map.Entry<String, List<KeyBinding>> catEntry : byCategory.entrySet()) {
                if (catEntry.getValue().size() > 5) continue;
                entries.add(
                        new Entry(
                                Entry.Kind.HEADER,
                                StatCollector.translateToLocal(catEntry.getKey()),
                                null,
                                null,
                                null));
                for (KeyBinding binding : catEntry.getValue())
                    entries.add(new Entry(Entry.Kind.MOD_KEY, null, null, null, binding));
            }
        }

        private void buildGroup(String group) {
            if (group.equals(GROUP_CONFLICTS)) {
                // 冲突键位组 (筛选视图): every currently-conflicting binding, editable to resolve the
                // conflicts. Keys listed here may still appear in their own groups below; that is fine.
                for (KeyBinding binding : KeyManager.getConflictingKeyBindings()) {
                    if (KeyManager.isNeiKeyByDescription(binding.getKeyDescription())) {
                        String ident = binding.getKeyDescription().substring("nei.options.keys.".length());
                        entries.add(new Entry(Entry.Kind.NEI_KEY, null, null, ident, null));
                    } else {
                        entries.add(new Entry(Entry.Kind.MOD_KEY, null, null, null, binding));
                    }
                }
                // 冲突涉及的原版键：可编辑（与原版"控制"界面共享同一 KeyBinding，双向同步），上方显示"原版按键"分类头
                List<KeyBinding> vanilla = KeyManager.getConflictingVanillaBindings();
                if (!vanilla.isEmpty()) {
                    entries.add(
                            new Entry(
                                    Entry.Kind.HEADER,
                                    StatCollector.translateToLocal("nei.options.keys.vanilla"),
                                    null,
                                    null,
                                    null));
                    for (KeyBinding vb : vanilla) entries.add(new Entry(Entry.Kind.MOD_KEY, null, null, null, vb));
                }
                return;
            }

            if (group.equals(GROUP_NEI)) {
                // NEI 组: every NEI key grouped back into its categories (物品栏/世界/NEI合成/...).
                Map<String, List<String>> byCategory = new LinkedHashMap<>();
                for (String ident : KeyManager.getRegisteredKeyNames()) {
                    byCategory.computeIfAbsent(KeyManager.getCategory(ident), cat -> new ArrayList<>()).add(ident);
                }
                for (Map.Entry<String, List<String>> catEntry : byCategory.entrySet()) {
                    entries.add(
                            new Entry(
                                    Entry.Kind.HEADER,
                                    StatCollector.translateToLocal(catEntry.getKey()),
                                    null,
                                    null,
                                    null));
                    for (String ident : catEntry.getValue())
                        entries.add(new Entry(Entry.Kind.NEI_KEY, null, null, ident, null));
                }
                return;
            }

            // A third-party mod's own group.
            for (KeyBinding binding : modBindingsByCategory().getOrDefault(group, new ArrayList<>())) {
                entries.add(new Entry(Entry.Kind.MOD_KEY, null, null, null, binding));
            }
        }

        Entry entryAt(int slot) {
            return entries.get(slot);
        }

        void resize() {
            int width = Math.min(parentScreen.width - 80, 320);
            setSize((parentScreen.width - width) / 2, 20, width, parentScreen.height - 50);
        }

        @Override
        public void drawBackground(float frame) {
            Rectangle sbar = scrollbarBounds();
            drawRect(sbar.x, y, sbar.x + sbar.width, y + height, 0xFF000000);
        }

        @Override
        public void drawOverlay(float frame) {
            OptionScrollPane.drawOverlay(y, height, parentScreen.width, zLevel);
        }

        @Override
        public Dimension scrollbarDim() {
            Dimension dim = super.scrollbarDim();
            dim.width = 6;
            return dim;
        }

        @Override
        public int scrollbarGuideAlignment() {
            return 0;
        }

        @Override
        public int getSlotHeight(int slot) {
            return 22;
        }

        @Override
        protected int getNumSlots() {
            return entries.size();
        }

        int slotWidth() {
            return windowBounds().width;
        }

        private String modifierText(int mask) {
            if (mask == 0) return StatCollector.translateToLocal("nei.options.keys.none");
            String name = NEIKeyboardUtils.getHashName(mask);
            return name.isEmpty() ? "?" : name;
        }

        private String mainKeyText(String ident) {
            int mainKey = KeyManager.getMainKeyCode(ident);
            return mainKey == Keyboard.KEY_NONE ? StatCollector.translateToLocal("nei.options.keys.unbound")
                    : NEIKeyboardUtils.getKeyName(mainKey);
        }

        private String mainKeyText(KeyBinding binding) {
            int code = binding.getKeyCode();
            return code == Keyboard.KEY_NONE ? StatCollector.translateToLocal("nei.options.keys.unbound")
                    : NEIKeyboardUtils.getKeyName(code);
        }

        /**
         * Right-aligned button layout for a NEI key. Returns {modX, mainX, modW, mainW}; the modifier button is absent
         * (modW == 0) for plain-only (强制单键) keys.
         */
        private int[] layout(String ident, String modText, String mainText) {
            int mainW = Math.max(70, getStringWidth(mainText) + 8);
            boolean showMod = !KeyManager.isPlainOnlyKey(ident);
            int modW = showMod ? Math.max(50, getStringWidth(modText) + 8) : 0;
            int plusW = showMod ? 10 : 0;
            int groupX = slotWidth() - (mainW + plusW + modW);
            return new int[] { groupX, groupX + modW + plusW, modW, mainW };
        }

        @Override
        protected void drawSlot(int slotIndex, int x, int y, int mx, int my, float frame) {
            Entry e = entries.get(slotIndex);
            if (e.kind == Entry.Kind.HEADER) {
                // 分类标题：仿原版按键设置，显示一行居中文字
                drawStringC(e.title, x, y, slotWidth(), 22, 0xFFFFFF);
                return;
            }

            if (e.kind == Entry.Kind.MENU_ITEM) {
                // 子菜单入口：整行按钮，居中显示组名，悬停高亮
                boolean hover = new Rectangle(0, 0, slotWidth(), 20).contains(mx, my);
                // 滚动条黑色底会遗留黑色 GL 颜色，导致每帧第一个按钮纹理被染色发黑；先重置为白色
                GL11.glColor4f(1, 1, 1, 1);
                LayoutManager.drawButtonBackground(x, y, slotWidth(), 20, true, hover ? 2 : 1);
                drawStringC(e.title, x, y, slotWidth(), 20, hover ? 0xFFFFFFA0 : 0xFFE0E0E0);
                return;
            }

            boolean active = listening && slotIndex == selectedIndex;
            if (e.kind == Entry.Kind.NEI_KEY) {
                drawNeiKeyRow(e.ident, x, y, mx, my, active);
            } else {
                drawModKeyRow(e.binding, x, y, mx, my, active);
            }
        }

        private void drawNeiKeyRow(String ident, int x, int y, int mx, int my, boolean active) {
            KeyBinding binding = KeyManager.getKeyBinding(ident);
            if (binding == null) return;

            String prefix = active ? StatCollector.translateToLocal("nei.options.keys.awaiting")
                    : StatCollector.translateToLocal(binding.getKeyDescription());

            String modText = modifierText(KeyManager.getModifierMask(ident));
            String mainText;
            if (active) {
                // 监听改键：主键按钮用 1.7 原版效果把当前键位用 >< 框住（键名黄色）
                String cur = mainKeyText(ident);
                mainText = EnumChatFormatting.WHITE + "> "
                        + EnumChatFormatting.YELLOW
                        + cur
                        + EnumChatFormatting.WHITE
                        + " <";
            } else {
                mainText = mainKeyText(ident);
            }

            int[] l = layout(ident, modText, mainText);
            int modX = l[0];
            int mainX = l[1];
            int modW = l[2];
            int mainW = l[3];

            // GUI-context keys get a small tag in the empty area left of the row; the description and
            // buttons keep their current positions. The tag is refined by the key's actual context so
            // search-field, recipe-view and general-overlay keys can be told apart at a glance.
            KeyManager.KeyContext keyCtx = KeyManager.getKeyContext(ident);
            if (keyCtx != KeyManager.KeyContext.WORLD) {
                String tagKey;
                switch (keyCtx) {
                    case SEARCH:
                        tagKey = "nei.options.keys.context_search";
                        break;
                    case RECIPE:
                        tagKey = "nei.options.keys.context_recipe";
                        break;
                    default:
                        tagKey = "nei.options.keys.gui_only";
                }
                String tag = StatCollector.translateToLocal(tagKey);
                fontRenderer.drawString(tag, x - fontRenderer.getStringWidth(tag) - 6, y + 6, 0xFFA0A0A0);
            }
            String cropped = NEIClientUtils.cropText(fontRenderer, prefix, Math.max(modX - 10, 0));
            fontRenderer.drawString(cropped, x + 10, y + 6, 0xFFFFFF);

            if (modW > 0) {
                boolean modHover = new Rectangle(modX, 0, modW, 20).contains(mx, my);
                LayoutManager.drawButtonBackground(x + modX, y, modW, 20, true, modHover ? 2 : 1);
                drawStringC(modText, x + modX, y, modW, 20, modHover ? 0xFFFFFFA0 : 0xFFE0E0E0);
                drawStringC("+", x + modX + modW, y, 10, 20, 0xFFA0A0A0);
            }

            boolean mainHover = new Rectangle(mainX, 0, mainW, 20).contains(mx, my);
            LayoutManager.drawButtonBackground(x + mainX, y, mainW, 20, true, active || mainHover ? 2 : 1);
            int mainColor = 0xFFE0E0E0;
            if (active || mainHover) {
                mainColor = 0xFFFFFFA0;
            } else if (KeyManager.isKeyConflicting(ident)) {
                // 冲突按键：仿原版标红
                mainColor = 0xFFFF5555;
            }
            drawStringC(mainText, x + mainX, y, mainW, 20, mainColor);
        }

        private void drawModKeyRow(KeyBinding binding, int x, int y, int mx, int my, boolean active) {
            String prefix = active ? StatCollector.translateToLocal("nei.options.keys.awaiting")
                    : StatCollector.translateToLocal(binding.getKeyDescription());

            String mainText;
            if (active) {
                String cur = mainKeyText(binding);
                mainText = EnumChatFormatting.WHITE + "> "
                        + EnumChatFormatting.YELLOW
                        + cur
                        + EnumChatFormatting.WHITE
                        + " <";
            } else {
                mainText = mainKeyText(binding);
            }

            int mainW = Math.max(70, getStringWidth(mainText) + 8);
            int mainX = slotWidth() - mainW;

            String cropped = NEIClientUtils.cropText(fontRenderer, prefix, Math.max(mainX - 10, 0));
            fontRenderer.drawString(cropped, x + 10, y + 6, 0xFFFFFF);

            boolean mainHover = new Rectangle(mainX, 0, mainW, 20).contains(mx, my);
            LayoutManager.drawButtonBackground(x + mainX, y, mainW, 20, true, active || mainHover ? 2 : 1);
            int mainColor = 0xFFE0E0E0;
            if (active || mainHover) {
                mainColor = 0xFFFFFFA0;
            } else if (KeyManager.isKeyBindingConflicting(binding)) {
                mainColor = 0xFFFF5555;
            }
            drawStringC(mainText, x + mainX, y, mainW, 20, mainColor);
        }

        private int cycleModifier(int current, boolean required) {
            int[] cycle = required ? REQUIRED_MOD_CYCLE : FREE_MOD_CYCLE;
            for (int i = 0; i < cycle.length; i++) {
                if (cycle[i] == current) return cycle[(i + 1) % cycle.length];
            }
            return cycle[0];
        }

        @Override
        protected void slotClicked(int slotIndex, int button, int mx, int my, int count) {
            if (slotIndex < 0 || slotIndex >= getNumSlots()) return;
            Entry e = entries.get(slotIndex);
            if (e.kind == Entry.Kind.HEADER) return;

            if (e.kind == Entry.Kind.MENU_ITEM) {
                if (button == 0) enterGroup(e.groupId);
                return;
            }

            if (e.kind == Entry.Kind.NEI_KEY) {
                String ident = e.ident;
                int[] l = layout(ident, modifierText(KeyManager.getModifierMask(ident)), mainKeyText(ident));
                int modX = l[0];
                int modW = l[2];

                if (modW > 0 && new Rectangle(modX, 0, modW, 20).contains(mx, my)) {
                    boolean required = KeyManager.isRequiredModifierKey(ident);
                    KeyManager.setModifier(ident, cycleModifier(KeyManager.getModifierMask(ident), required));
                    commit();
                    return;
                }

                if (button == 1) {
                    KeyManager.setMainKey(ident, Keyboard.KEY_NONE);
                    commit();
                } else if (button == 0) {
                    selectedIndex = slotIndex;
                    listening = true;
                }
                return;
            }

            // third-party mod key: single-key rebinding only
            if (button == 1) {
                e.binding.setKeyCode(Keyboard.KEY_NONE);
                commit();
            } else if (button == 0) {
                selectedIndex = slotIndex;
                listening = true;
            }
        }

        @Override
        public void mouseScrolled(int x, int y, int scroll) {
            scroll(-scroll);
        }
    }
}
