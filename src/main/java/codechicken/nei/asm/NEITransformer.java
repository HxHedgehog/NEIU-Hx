package codechicken.nei.asm;

import static codechicken.lib.asm.InsnComparator.findN;
import static codechicken.lib.asm.InsnComparator.findOnce;
import static codechicken.lib.asm.InsnComparator.getControlFlowLabels;
import static codechicken.lib.asm.InsnComparator.matches;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM5;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;

import java.util.Map;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import codechicken.lib.asm.ASMBlock;
import codechicken.lib.asm.ASMHelper;
import codechicken.lib.asm.ASMInit;
import codechicken.lib.asm.ASMReader;
import codechicken.lib.asm.ClassHeirachyManager;
import codechicken.lib.asm.InsnListSection;
import codechicken.lib.asm.ModularASMTransformer;
import codechicken.lib.asm.ModularASMTransformer.FieldWriter;
import codechicken.lib.asm.ModularASMTransformer.MethodInjector;
import codechicken.lib.asm.ModularASMTransformer.MethodReplacer;
import codechicken.lib.asm.ModularASMTransformer.MethodTransformer;
import codechicken.lib.asm.ModularASMTransformer.MethodWriter;
import codechicken.lib.asm.ObfMapping;
import cpw.mods.fml.relauncher.FMLLaunchHandler;

public class NEITransformer implements IClassTransformer {

    static {
        ASMInit.init();
    }

    private final boolean paranoidMode = Boolean.getBoolean("nei.paranoid");
    private final ModularASMTransformer transformer = new ModularASMTransformer();
    private final Map<String, ASMBlock> asmblocks = ASMReader.loadResource("/assets/nei/asm/blocks.asm");

    public NEITransformer() {
        if (FMLLaunchHandler.side().isClient()) {
            // Generates method to set the placed position of a mob spawner for the item
            // callback. More portable than
            // copying vanilla placement code
            transformer.add(
                    new MethodWriter(
                            ACC_PUBLIC,
                            new ObfMapping(
                                    "net/minecraft/block/BlockMobSpawner",
                                    "func_149689_a",
                                    "(Lnet/minecraft/world/World;IIILnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;)V"),
                            asmblocks.get("spawnerPlaced")));
        }

        // Removes trailing seperators from NBTTagList/Compound.toString because OCD
        transformer.add(
                new MethodInjector(
                        new ObfMapping("net/minecraft/nbt/NBTTagCompound", "toString", "()Ljava/lang/String;"),
                        asmblocks.get("n_commaFix"),
                        asmblocks.get("commaFix"),
                        true));
        transformer.add(
                new MethodInjector(
                        new ObfMapping("net/minecraft/nbt/NBTTagList", "toString", "()Ljava/lang/String;"),
                        asmblocks.get("n_commaFix"),
                        asmblocks.get("commaFix"),
                        true));

        transformer.add(
                new MethodTransformer(
                        new ObfMapping(
                                "net/minecraft/inventory/ContainerWorkbench",
                                "func_82846_b",
                                "(Lnet/minecraft/entity/player/EntityPlayer;I)Lnet/minecraft/item/ItemStack;")) {

                    @Override
                    public void transform(MethodNode mv) {
                        ASMHelper.logger.debug("NEI: Applying workbench fix");
                        InsnListSection key = findN(mv.instructions, asmblocks.get("n_workbenchFix").list).get(0);
                        key.insertBefore(asmblocks.get("workbenchFix").rawListCopy());
                    }
                });

        // put glint alpha into the buffer correctly for exporting
        transformer.add(
                new MethodReplacer(
                        new ObfMapping("net/minecraft/client/renderer/entity/RenderItem", "func_77018_a", "(IIIII)V"),
                        asmblocks.get("d_glintAlphaFix"),
                        asmblocks.get("glintAlphaFix")));

        if (FMLLaunchHandler.side().isClient()) {
            transformer.add(
                    new MethodWriter(
                            ACC_PUBLIC | ACC_STATIC,
                            new ObfMapping("net/minecraft/client/settings/KeyBinding", "func_74507_a", "(I)V"),
                            asmblocks.get("m_keyBindingOnTickAll")));

            transformer.add(
                    new MethodWriter(
                            ACC_PUBLIC | ACC_STATIC,
                            new ObfMapping("net/minecraft/client/settings/KeyBinding", "func_74510_a", "(IZ)V"),
                            asmblocks.get("m_keyBindingSetStateAll")));

            transformer.add(
                    new MethodInjector(
                            new ObfMapping("net/minecraft/client/settings/GameSettings", "func_74303_b", "()V"),
                            asmblocks.get("saveOptionsHook"),
                            true));

            transformer.add(
                    new MethodInjector(
                            new ObfMapping("net/minecraft/client/settings/GameSettings", "func_74303_b", "()V"),
                            asmblocks.get("n_saveOptionsSendSettings"),
                            asmblocks.get("saveOptionsRestoreHook"),
                            false));

            // Hide every mod key binding (NEI keys and other mods' keys) from the vanilla controls
            // screen (GuiKeyBindingList), keeping them fully functional. They are managed instead from
            // the NEI "键位设置" screen. The GuiKeyBindingList constructor clones gameSettings.keyBindings
            // into a local display array used both for the list capacity and for rendering; we inject a
            // single call to KeyBindingListHooks.trimModKeys right after that clone so the visible list
            // (and its size) only contains vanilla bindings. Done with a direct ClassNode walk (find the
            // ArrayUtils.clone call and the following ASTORE) instead of sequence-needle matching, which
            // is unreliable here.
            transformer.add(
                    new MethodTransformer(
                            new ObfMapping(
                                    "net/minecraft/client/gui/GuiKeyBindingList",
                                    "<init>",
                                    "(Lnet/minecraft/client/gui/GuiControls;Lnet/minecraft/client/Minecraft;)V")) {

                        @Override
                        public void transform(MethodNode mv) {
                            AbstractInsnNode clone = null;
                            for (AbstractInsnNode in = mv.instructions.getFirst(); in != null; in = in.getNext()) {
                                if (in.getOpcode() == Opcodes.INVOKESTATIC && in instanceof MethodInsnNode
                                        && ((MethodInsnNode) in).owner.equals("org/apache/commons/lang3/ArrayUtils")
                                        && ((MethodInsnNode) in).name.equals("clone")) {
                                    clone = in;
                                    break;
                                }
                            }
                            if (clone == null) throw new RuntimeException(
                                    "NEI: ArrayUtils.clone not found in GuiKeyBindingList.<init>");

                            AbstractInsnNode store = clone.getNext();
                            while (store != null && store.getOpcode() != Opcodes.ASTORE) store = store.getNext();
                            if (store == null) throw new RuntimeException(
                                    "NEI: ASTORE after ArrayUtils.clone not found in GuiKeyBindingList.<init>");

                            int var = ((VarInsnNode) store).var;
                            InsnList insns = new InsnList();
                            insns.add(new VarInsnNode(Opcodes.ALOAD, var));
                            insns.add(
                                    new MethodInsnNode(
                                            Opcodes.INVOKESTATIC,
                                            "codechicken/nei/asm/KeyBindingListHooks",
                                            "trimModKeys",
                                            "([Lnet/minecraft/client/settings/KeyBinding;)[Lnet/minecraft/client/settings/KeyBinding;"));
                            insns.add(new VarInsnNode(Opcodes.ASTORE, var));
                            mv.instructions.insert(store, insns);

                            // The display array is allocated (PUTFIELD) before its slots are filled by the
                            // loop below, so we must compact the trailing NEI-category nulls only once the
                            // whole constructor has run. Grab the field descriptor from the first PUTFIELD
                            // following the clone, then inject the compact just before the final RETURN.
                            FieldInsnNode storeField = null;
                            for (AbstractInsnNode in = store.getNext(); in != null; in = in.getNext()) {
                                if (in.getOpcode() == Opcodes.PUTFIELD && in instanceof FieldInsnNode) {
                                    storeField = (FieldInsnNode) in;
                                    break;
                                }
                            }
                            if (storeField == null) throw new RuntimeException(
                                    "NEI: field_148190_m store not found in GuiKeyBindingList.<init>");
                            AbstractInsnNode lastReturn = null;
                            for (AbstractInsnNode in = mv.instructions.getFirst(); in != null; in = in.getNext()) {
                                if (in.getOpcode() == Opcodes.RETURN) lastReturn = in;
                            }
                            if (lastReturn == null)
                                throw new RuntimeException("NEI: no RETURN found in GuiKeyBindingList.<init>");
                            InsnList compact = new InsnList();
                            compact.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            compact.add(new InsnNode(Opcodes.DUP));
                            compact.add(
                                    new FieldInsnNode(
                                            Opcodes.GETFIELD,
                                            storeField.owner,
                                            storeField.name,
                                            storeField.desc));
                            compact.add(
                                    new MethodInsnNode(
                                            Opcodes.INVOKESTATIC,
                                            "codechicken/nei/asm/KeyBindingListHooks",
                                            "compactEntries",
                                            "([Lnet/minecraft/client/gui/GuiListExtended$IGuiListEntry;)[Lnet/minecraft/client/gui/GuiListExtended$IGuiListEntry;"));
                            compact.add(
                                    new FieldInsnNode(
                                            Opcodes.PUTFIELD,
                                            storeField.owner,
                                            storeField.name,
                                            storeField.desc));
                            mv.instructions.insertBefore(lastReturn, compact);
                        }
                    });

            // Route the vanilla controls screen conflict detection through a combo-aware predicate.
            // GuiKeyBindingList$KeyEntry.drawEntry compares `keybinding.getKeyCode() ==
            // this.field_148282_b.getKeyCode()`; we replace that int comparison with
            // KeyBindingListHooks.conflicts(a, b) so NEI combo keys (with modifier bits stored in the
            // key code) are never treated as their plain main key and falsely flagged as conflicting.
            // Several getKeyCode() calls exist in the method (btnReset.enabled, getKeyDisplayString),
            // so we locate the conflict pair by structure: the last getKeyCode immediately followed by
            // IF_ICMPNE is the right-hand side of the conflict compare.
            transformer.add(
                    new MethodTransformer(
                            new ObfMapping(
                                    "net/minecraft/client/gui/GuiKeyBindingList$KeyEntry",
                                    "func_148279_a",
                                    "(IIIIILnet/minecraft/client/renderer/Tessellator;IIZ)V")) {

                        @Override
                        public void transform(MethodNode mv) {
                            ObfMapping getKeyCodeMap = new ObfMapping(
                                    "net/minecraft/client/settings/KeyBinding",
                                    "func_151463_i",
                                    "()I").toClassloading();
                            final String owner = getKeyCodeMap.s_owner;
                            final String name = getKeyCodeMap.s_name;
                            final String keyBindingDesc = "L" + owner + ";";

                            MethodInsnNode second = null;
                            for (AbstractInsnNode in = mv.instructions.getFirst(); in != null; in = in.getNext()) {
                                if (in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode) {
                                    MethodInsnNode min = (MethodInsnNode) in;
                                    if (min.owner.equals(owner) && min.name.equals(name)) {
                                        AbstractInsnNode next = in.getNext();
                                        if (next != null && next.getOpcode() == Opcodes.IF_ICMPNE) {
                                            second = min;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (second == null) throw new RuntimeException(
                                    "NEI: conflict getKeyCode compare not found in GuiKeyBindingList$KeyEntry.drawEntry");

                            MethodInsnNode first = null;
                            for (AbstractInsnNode in = second.getPrevious(); in != null; in = in.getPrevious()) {
                                if (in.getOpcode() == Opcodes.INVOKEVIRTUAL && in instanceof MethodInsnNode) {
                                    MethodInsnNode min = (MethodInsnNode) in;
                                    if (min.owner.equals(owner) && min.name.equals(name)) {
                                        first = min;
                                        break;
                                    }
                                }
                            }
                            if (first == null) throw new RuntimeException(
                                    "NEI: first getKeyCode of conflict compare not found in GuiKeyBindingList$KeyEntry.drawEntry");

                            // verify the expected shape: ... getKeyCode -> ALOAD 0 -> GETFIELD LKeyBinding; ->
                            // getKeyCode -> IF_ICMPNE
                            AbstractInsnNode firstNext = first.getNext();
                            AbstractInsnNode secondPrev = second.getPrevious();
                            if (firstNext == null || firstNext.getOpcode() != Opcodes.ALOAD
                                    || ((VarInsnNode) firstNext).var != 0
                                    || secondPrev == null
                                    || secondPrev.getOpcode() != Opcodes.GETFIELD
                                    || !(secondPrev instanceof FieldInsnNode)
                                    || !((FieldInsnNode) secondPrev).desc.equals(keyBindingDesc)) {
                                throw new RuntimeException(
                                        "NEI: unexpected conflict compare structure in GuiKeyBindingList$KeyEntry.drawEntry");
                            }

                            // The leading getKeyCode() result would be unused once the pair becomes a
                            // hook call: drop it, keeping the ALOAD (arg 1) and ALOAD 0 / GETFIELD (arg 2).
                            // Capture the jump BEFORE replacing the node: InsnList.set detaches the old
                            // node, nulling its next pointer, so reading second.getNext() afterwards
                            // would always return null.
                            AbstractInsnNode cmp = second.getNext();
                            if (cmp == null || cmp.getOpcode() != Opcodes.IF_ICMPNE) throw new RuntimeException(
                                    "NEI: IF_ICMPNE after conflict compare not found in GuiKeyBindingList$KeyEntry.drawEntry");
                            mv.instructions.remove(first);
                            MethodInsnNode hook = new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    "codechicken/nei/asm/KeyBindingListHooks",
                                    "conflicts",
                                    "(" + keyBindingDesc + keyBindingDesc + ")Z");
                            mv.instructions.set(second, hook);
                            mv.instructions.set(cmp, new JumpInsnNode(Opcodes.IFEQ, ((JumpInsnNode) cmp).label));
                        }
                    });
        }

        String GuiContainer = "net/minecraft/client/gui/inventory/GuiContainer";
        // add manager field
        transformer.add(
                new FieldWriter(
                        ACC_PUBLIC,
                        new ObfMapping(GuiContainer, "manager", "Lcodechicken/nei/guihook/GuiContainerManager;")));

        // Fill out getManager in GuiContainerManager
        transformer.add(
                new MethodWriter(
                        ACC_PUBLIC | ACC_STATIC,
                        new ObfMapping(
                                "codechicken/nei/guihook/GuiContainerManager",
                                "getManager",
                                "(Lnet/minecraft/client/gui/inventory/GuiContainer;)Lcodechicken/nei/guihook/GuiContainerManager;"),
                        asmblocks.get("m_getManager")));

        // Generate load method
        transformer.add(
                new MethodWriter(
                        ACC_PUBLIC,
                        new ObfMapping(GuiContainer, "func_146280_a", "(Lnet/minecraft/client/Minecraft;II)V"),
                        asmblocks.get("m_setWorldAndResolution")));

        // Generate handleKeyboardInput method
        transformer.add(
                new MethodWriter(
                        ACC_PUBLIC,
                        new ObfMapping(GuiContainer, "func_146282_l", "()V"),
                        asmblocks.get("m_handleKeyboardInput")));

        // Generate handleKeyboardInput method
        transformer.add(
                new MethodWriter(
                        ACC_PUBLIC,
                        new ObfMapping(GuiContainer, "func_146282_l", "()V"),
                        asmblocks.get("m_handleKeyboardInput")));

        // Generate handleKeyboardInput method
        transformer.add(
                new MethodWriter(
                        ACC_PUBLIC,
                        new ObfMapping(GuiContainer, "func_146282_l", "()V"),
                        asmblocks.get("m_handleKeyboardInput")));

        // Generate handleMouseInput method
        transformer.add(
                new MethodWriter(
                        ACC_PUBLIC,
                        new ObfMapping(GuiContainer, "func_146274_d", "()V"),
                        asmblocks.get("m_handleMouseInput")));

        addProtectedForwarder(
                new ObfMapping(GuiContainer, "func_73869_a", "(CI)V"),
                new ObfMapping(
                        "codechicken/nei/guihook/GuiContainerManager",
                        "callKeyTyped",
                        "(Lnet/minecraft/client/gui/inventory/GuiContainer;CI)V"));

        addProtectedForwarder(
                new ObfMapping(GuiContainer, "func_146984_a", "(Lnet/minecraft/inventory/Slot;III)V"),
                new ObfMapping(
                        "codechicken/nei/guihook/DefaultSlotClickHandler",
                        "callHandleMouseClick",
                        "(Lnet/minecraft/client/gui/inventory/GuiContainer;Lnet/minecraft/inventory/Slot;III)V"));

        // Inject preDraw at the start of drawScreen
        transformer.add(
                new MethodInjector(
                        new ObfMapping(GuiContainer, "func_73863_a", "(IIF)V"),
                        asmblocks.get("preDraw"),
                        true));

        // Inject objectUnderMouse check before drawing slot highlights
        transformer.add(
                new MethodInjector(
                        new ObfMapping(GuiContainer, "func_73863_a", "(IIF)V"),
                        asmblocks.get("n_objectUnderMouse"),
                        asmblocks.get("objectUnderMouse"),
                        false));

        // Inject renderObjects after drawGuiContainerForegroundLayer
        transformer.add(
                new MethodInjector(
                        new ObfMapping(GuiContainer, "func_73863_a", "(IIF)V"),
                        asmblocks.get("n_renderObjects"),
                        asmblocks.get("renderObjects"),
                        false));

        // Replace default renderToolTip with delegate
        transformer.add(
                new MethodReplacer(
                        new ObfMapping(GuiContainer, "func_73863_a", "(IIF)V"),
                        asmblocks.get("d_renderToolTip"),
                        asmblocks.get("renderTooltips")));

        // Replace zLevel = 200 with zLevel = 500 in drawItemStack
        transformer.add(
                new MethodReplacer(
                        new ObfMapping(
                                GuiContainer,
                                "func_146982_a",
                                "(Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V"),
                        asmblocks.get("d_zLevel"),
                        asmblocks.get("zLevel")));

        // Replace default renderItem with delegate and slot overlay/underlay
        transformer.add(
                new MethodReplacer(
                        new ObfMapping(GuiContainer, "func_146977_a", "(Lnet/minecraft/inventory/Slot;)V"),
                        asmblocks.get("d_drawSlot"),
                        asmblocks.get("drawSlot")));

        // Inject mouseClicked hook at the start of mouseClicked
        transformer.add(
                new MethodInjector(
                        new ObfMapping(GuiContainer, "func_73864_a", "(III)V"),
                        asmblocks.get("mouseClicked"),
                        true));

        // Replace general handleMouseClicked call with delegate
        transformer.add(
                new MethodReplacer(
                        new ObfMapping(GuiContainer, "func_73864_a", "(III)V"),
                        asmblocks.get("d_handleMouseClick"),
                        asmblocks.get("handleMouseClick"))); // mouseClicked
        transformer.add(
                new MethodReplacer(
                        new ObfMapping(GuiContainer, "func_146273_a", "(IIIJ)V"),
                        asmblocks.get("d_handleMouseClick"),
                        asmblocks.get("handleMouseClick"))); // mouseClickMove
        transformer.add(
                new MethodReplacer(
                        new ObfMapping(GuiContainer, "func_146286_b", "(III)V"),
                        asmblocks.get("d_handleMouseClick"),
                        asmblocks.get("handleMouseClick"))); // mouseMovedOrUp
        transformer.add(
                new MethodReplacer(
                        new ObfMapping(GuiContainer, "func_73869_a", "(CI)V"),
                        asmblocks.get("d_handleMouseClick"),
                        asmblocks.get("handleMouseClick"))); // keyTyped
        transformer.add(
                new MethodReplacer(
                        new ObfMapping(GuiContainer, "func_146983_a", "(I)Z"),
                        asmblocks.get("d_handleMouseClick"),
                        asmblocks.get("handleMouseClick"))); // checkHotbarKeys

        // Write delegate for handleMouseClicked
        transformer.add(
                new MethodWriter(
                        ACC_PUBLIC,
                        new ObfMapping(GuiContainer, "managerHandleMouseClick", "(Lnet/minecraft/inventory/Slot;III)V"),
                        asmblocks.get("m_managerHandleMouseClick")));

        // Inject mouseDragged hook after super call in mouseDragged
        transformer.add(
                new MethodInjector(
                        new ObfMapping(GuiContainer, "func_146273_a", "(IIIJ)V"),
                        asmblocks.get("n_mouseDragged"),
                        asmblocks.get("mouseDragged"),
                        false));

        // Inject overrideMouseUp at the start of mouseMovedOrUp
        transformer.add(
                new MethodInjector(
                        new ObfMapping(GuiContainer, "func_146286_b", "(III)V"),
                        asmblocks.get("overrideMouseUp"),
                        true));

        // Inject mouseUp at the end of main elseif chain in mouseMovedOrUp
        transformer.add(new MethodTransformer(new ObfMapping(GuiContainer, "func_146286_b", "(III)V")) {

            @Override
            public void transform(MethodNode mv) {
                ASMHelper.logger.debug("NEI: Injecting mouseUp call");
                ASMBlock gotoBlock = asmblocks.get("n_mouseUpGoto").copy();
                ASMBlock needleBlock = asmblocks.get("n_mouseUp").copy();
                ASMBlock injectionBlock = asmblocks.get("mouseUp").copy();

                gotoBlock.mergeLabels(injectionBlock);
                findOnce(mv.instructions, gotoBlock.list).replace(gotoBlock.list.list);

                InsnListSection needle = findOnce(mv.instructions, needleBlock.list);
                injectionBlock.mergeLabels(needleBlock.applyLabels(needle));
                needle.insertBefore(injectionBlock.list.list);
            }
        });

        // Replace general handleSlotClick call with delegate
        transformer.add(
                new MethodReplacer(
                        new ObfMapping(GuiContainer, "func_146984_a", "(Lnet/minecraft/inventory/Slot;III)V"),
                        asmblocks.get("d_handleSlotClick"),
                        asmblocks.get("handleSlotClick")));

        // Inject lastKeyTyped at the start of keyTyped
        transformer.add(
                new MethodInjector(
                        new ObfMapping(GuiContainer, "func_73869_a", "(CI)V"),
                        asmblocks.get("lastKeyTyped"),
                        true));

        // Inject updateScreen hook after super call
        transformer.add(
                new MethodInjector(
                        new ObfMapping(GuiContainer, "func_73876_c", "()V"),
                        asmblocks.get("n_updateScreen"),
                        asmblocks.get("updateScreen"),
                        false));

        // Cancel tab click calls when tabs are obscured
        transformer.add(
                new MethodInjector(
                        new ObfMapping(
                                "net/minecraft/client/gui/inventory/GuiContainerCreative",
                                "func_147049_a",
                                "(Lnet/minecraft/creativetab/CreativeTabs;II)Z"),
                        asmblocks.get("handleTabClick"),
                        true));

        // Cancel tab tooltip rendering when tabs are obscured
        transformer.add(
                new MethodInjector(
                        new ObfMapping(
                                "net/minecraft/client/gui/inventory/GuiContainerCreative",
                                "func_147052_b",
                                "(Lnet/minecraft/creativetab/CreativeTabs;II)Z"),
                        asmblocks.get("renderTabTooltip"),
                        true));

        String[] buttons = new String[] { "CancelButton", "ConfirmButton", "PowerButton" };
        String[] this_fields = new String[] { "field_146146_o", "field_146147_o", "field_146150_o" };
        for (int i = 0; i < 3; i++) {
            ObfMapping m = new ObfMapping(
                    "net/minecraft/client/gui/inventory/GuiBeacon$" + buttons[i],
                    "func_146111_b",
                    "(II)V");
            InsnListSection l = asmblocks.get("beaconButtonObscured").list.copy();
            FieldInsnNode this_ref = ((FieldInsnNode) l.get(1));
            this_ref.owner = m.toClassloading().s_owner;
            if (ObfMapping.obfuscated) // missing srg mappings for inner outer reference fields
                this_ref.name = ObfMapping.obfMapper.mapFieldName(null, this_fields[i], null);
            transformer.add(new MethodInjector(m, l.list, true));
        }
    }

    private void addProtectedForwarder(ObfMapping called, ObfMapping caller) {

        InsnList forward1 = new InsnList();
        InsnList forward2 = new InsnList();

        ObfMapping publicCall = new ObfMapping(called.s_owner, "public_" + called.s_name, called.s_desc);
        Type[] args = Type.getArgumentTypes(caller.s_desc);
        for (int i = 0; i < args.length; i++) {
            forward1.add(new VarInsnNode(args[i].getOpcode(ILOAD), i));
            forward2.add(new VarInsnNode(args[i].getOpcode(ILOAD), i));
        }

        forward1.add(publicCall.toInsn(INVOKEVIRTUAL));
        forward2.add(called.toClassloading().toInsn(INVOKEVIRTUAL));
        forward1.add(new InsnNode(Type.getReturnType(called.s_desc).getOpcode(IRETURN)));
        forward2.add(new InsnNode(Type.getReturnType(called.s_desc).getOpcode(IRETURN)));

        transformer.add(new MethodWriter(ACC_PUBLIC | ACC_STATIC, caller, forward1));
        transformer.add(new MethodWriter(ACC_PUBLIC, publicCall, forward2));
    }

    private final ObfMapping c_GuiContainer = new ObfMapping("net/minecraft/client/gui/inventory/GuiContainer")
            .toClassloading();

    /**
     * Adds super.updateScreen() to non implementing GuiContainer subclasses
     */
    public byte[] transformSubclasses(String name, byte[] bytes) {
        if (ClassHeirachyManager.classExtends(name, c_GuiContainer.javaClass())) {
            ClassNode cnode = ASMHelper.createClassNode(bytes);

            ObfMapping methodmap = new ObfMapping(cnode.superName, "func_73876_c", "()V").toClassloading();

            InsnListSection supercall = new InsnListSection();
            supercall.add(new VarInsnNode(ALOAD, 0));
            supercall.add(methodmap.toInsn(INVOKESPECIAL));

            boolean changed = false;
            for (MethodNode mv : cnode.methods) {
                if (methodmap.matches(mv)) {
                    if (matches(new InsnListSection(mv.instructions), supercall, getControlFlowLabels(mv.instructions))
                            == null) {
                        mv.instructions.insert(supercall.list);
                        ASMHelper.logger.debug("Inserted super call into " + methodmap);
                        changed = true;
                    }
                }
            }

            if (changed) bytes = ASMHelper.createBytes(cnode, COMPUTE_MAXS | COMPUTE_FRAMES);
        }
        return bytes;
    }

    @Override
    public byte[] transform(String name, String tname, byte[] bytes) {
        if (bytes == null) return null;
        try {
            if (FMLLaunchHandler.side().isClient()) {
                if (name.equals("net.minecraftforge.oredict.OreDictionary")) {
                    ClassReader cr = new ClassReader(bytes);
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
                    cr.accept(new OreDictionaryVisitor(ASM5, cw), 0);
                    bytes = cw.toByteArray();
                }
                bytes = transformSubclasses(name, bytes);
            }
            if (paranoidMode && !tname.startsWith("codechicken.nei")) {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(0);
                GuiContainerManagerAPITransformer cv = new GuiContainerManagerAPITransformer(ASM5, cw, tname);
                cr.accept(cv, ClassReader.SKIP_DEBUG);
                if (cv.isChanged()) bytes = cw.toByteArray();
            }

            bytes = transformer.transform(name, bytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return bytes;
    }

}
