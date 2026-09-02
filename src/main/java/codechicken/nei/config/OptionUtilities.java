package codechicken.nei.config;

import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import codechicken.nei.Image;
import codechicken.nei.LayoutManager;
import codechicken.nei.guihook.GuiContainerManager;

public class OptionUtilities extends OptionStringSet {

    public OptionUtilities(String name) {
        super(name);
        options.add("time");
        options.add("rain");
        options.add("heal");
        options.add("delete");
        options.add("magnet");
        options.add("gamemode");
        options.add("enchant");
        options.add("potion");
        options.add("item");
        groups.put("gamemode", "creative");
        groups.put("gamemode", "creative+");
        groups.put("gamemode", "adventure");
    }

    @Override
    public void drawIcons() {
        int x = buttonX();
        LayoutManager.drawIcon(x + 4, 4, new Image(84, 4, 12, 12));
        x += 24;
        LayoutManager.drawIcon(x + 4, 4, new Image(4, 4, 12, 12));
        x += 24;
        LayoutManager.drawIcon(x + 4, 4, new Image(52, 20, 12, 12));
        x += 24;
        LayoutManager.drawIcon(x + 4, 4, new Image(36, 4, 12, 12));
        x += 24;
        LayoutManager.drawIcon(x + 4, 4, new Image(68, 20, 12, 12));
        x += 24;
        LayoutManager.drawIcon(x + 4, 4, new Image(20, 4, 12, 12));
        x += 24;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LIGHTING_BIT);
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        ItemStack sword = new ItemStack(Items.diamond_sword);
        sword.addEnchantment(Enchantment.sharpness, 1);
        GuiContainerManager.drawItem(x + 2, 2, sword);
        x += 24;
        GuiContainerManager.drawItem(x + 2, 2, new ItemStack(Items.potionitem));
        x += 24;
        GuiContainerManager.drawItem(x + 2, 2, new ItemStack(Blocks.stone));
        GL11.glPopAttrib();
    }
}
