package ghostframe;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Мембрана фантома — декоративный инструмент. Рамка становится невидимой, а табличка превращается в
 * висящий текст ({@code text_display}) — доску и столб прячет маркер, текст остаётся читаемым.
 * Серверный мод: ванильный клиент прячет рамку сам ({@code ItemFrameRenderer} не рисует модель при
 * {@code Invisible}), а дисплеи — ванильные сущности, клиенту нечего ставить.
 */
public class GhostFrame implements ModInitializer {
	public static final String MOD_ID = "ghostframe";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		UseEntityCallback.EVENT.register(FrameMembrane::onUseEntity);
		UseBlockCallback.EVENT.register(SignGhost::onUseBlock);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				SignGhost.onBlockBreak(level, player, pos, state));
		// маркер могут снести извне — подмести осиротевший текст и на старте, и раз в 30 с
		ServerLifecycleEvents.SERVER_STARTED.register(SignGhost::cleanupOrphans);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 600 == 0) {
				SignGhost.cleanupOrphans(server);
			}
		});
		LOGGER.info("GhostFrame: мембрана фантома прячет рамки и таблички");

		if (System.getProperty("ghostframe.selftest") != null || System.getenv("GHOSTFRAME_SELFTEST") != null) {
			ServerTickEvents.START_SERVER_TICK.register(SelfTest::onTick);
			LOGGER.info("GhostFrame: включена самопроверка (ghostframe.selftest / GHOSTFRAME_SELFTEST)");
		}
	}
}
