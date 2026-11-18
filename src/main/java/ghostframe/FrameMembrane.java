package ghostframe;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Правый клик мембраной фантома по рамке переключает её невидимость. Мембрана тратится
 * (в креативе — нет), предмет в рамке не трогается, хитбокс остаётся прежним, поэтому
 * класть и вращать предметы в невидимой рамке можно как в обычной.
 * <p>
 * С зажатым Shift клик уходит ванили: так мембрану всё ещё можно положить в рамку как предмет.
 */
public final class FrameMembrane {
	/** Ванильный {@code ServerGamePacketListenerImpl} проверяет дистанцию сам, но при отмене события эта проверка пропускается. */
	private static final double INTERACTION_BUFFER = 3.0;

	private FrameMembrane() {
	}

	public static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity,
			EntityHitResult hit) {
		if (!(entity instanceof ItemFrame frame)) {
			return InteractionResult.PASS;
		}
		ItemStack held = player.getItemInHand(hand);
		if (!held.is(Items.PHANTOM_MEMBRANE)) {
			return InteractionResult.PASS;
		}
		if (player.isSecondaryUseActive()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			// Мод серверный; клиентская ветка нужна только чтобы не предсказывать попадание предмета в рамку.
			return InteractionResult.SUCCESS;
		}
		if (player.isSpectator() || !player.isWithinEntityInteractionRange(frame, INTERACTION_BUFFER)) {
			return InteractionResult.PASS;
		}

		boolean hide = !frame.isInvisible();
		frame.setInvisible(hide);
		frame.playSound(hide ? SoundEvents.PHANTOM_FLAP : SoundEvents.PHANTOM_SWOOP, 0.8F, hide ? 0.9F : 1.4F);
		held.consume(1, player);
		player.swing(hand, true);
		return InteractionResult.SUCCESS_SERVER;
	}
}
