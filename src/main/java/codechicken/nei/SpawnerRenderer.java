package codechicken.nei;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import codechicken.core.ClientUtils;
import codechicken.lib.render.CCRenderState;

public class SpawnerRenderer implements IItemRenderer {

    @Override
    public boolean handleRenderType(ItemStack item, ItemRenderType type) {
        return true;
    }

    public void renderInventoryItem(RenderBlocks render, ItemStack item) {
        int meta = item.getItemDamage();
        if (meta == 0) meta = ItemMobSpawner.idPig;
        String bossName = BossStatus.bossName;
        int bossTimeout = BossStatus.statusBarTime;
        boolean bossHasColorModifier = BossStatus.hasColorModifier;
        Entity entity = ItemMobSpawner.getEntity(meta);
        // 记录光贴图纹理启用状态：图标内的实体渲染（RenderLiving）结尾会重新启用光贴图纹理，
        // 渲染后必须恢复原状，否则世界路径（地面刷怪笼 EntityItem → renderItem(ENTITY)）会把
        // 光贴图置为不一致状态，导致后续世界实体失去光贴图 → 全亮度 → 自发光。
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        boolean lightmapTextureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        try {
            // 原版 RenderItem 渲染方块物品前会 glEnable(GL_ALPHA_TEST) 保证镂空；
            // 本渲染器走 ForgeHooksClient.renderInventoryItem 路径，该路径不保证 alpha test 状态，
            // 若前面渲染的是 2D 物品（其渲染后会关闭 alpha test），笼子贴图透明部分将不镂空 → 显示为实心方块。
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            CCRenderState.changeTexture(TextureMap.locationBlocksTexture);
            render.renderBlockAsItem(Blocks.mob_spawner, 0, 1F);
            if (entity != null) {
                entity.setWorld(render.minecraftRB.theWorld);
                GL11.glPushMatrix();
                float f1 = 0.4375F;
                if (entity.getShadowSize() > 1.5) f1 = 0.1F;
                GL11.glRotatef((float) (ClientUtils.getRenderTime() * 10), 0.0F, 1.0F, 0.0F);
                GL11.glRotatef(-20F, 1.0F, 0.0F, 0.0F);
                GL11.glTranslatef(0.0F, -0.4F, 0.0F);
                GL11.glScalef(f1, f1, f1);
                entity.setLocationAndAngles(0, 0, 0, 0.0F, 0.0F);
                RenderManager.instance.renderEntityWithPosYaw(entity, 0.0D, 0.0D, 0.0D, 0.0F, 0);
                GL11.glPopMatrix();
            }
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        } catch (Exception e) {
            if (Tessellator.instance.isDrawing) Tessellator.instance.draw();
        }
        // 恢复光贴图纹理启用状态，防止泄漏到后续世界实体渲染
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        if (lightmapTextureEnabled) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        } else {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        BossStatus.bossName = bossName;
        BossStatus.statusBarTime = bossTimeout;
        BossStatus.hasColorModifier = bossHasColorModifier;
    }

    @Override
    public void renderItem(ItemRenderType type, ItemStack item, Object... data) {
        switch (type) {
            case EQUIPPED:
            case EQUIPPED_FIRST_PERSON:
                GL11.glTranslatef(0.5F, 0.5F, 0.5F);
            case INVENTORY:
            case ENTITY:
                renderInventoryItem((RenderBlocks) data[0], item);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean shouldUseRenderHelper(ItemRenderType type, ItemStack item, ItemRendererHelper helper) {
        return true;
    }
}
