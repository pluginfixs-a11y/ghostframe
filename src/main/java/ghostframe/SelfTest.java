package ghostframe;

import java.util.List;
import com.mojang.math.Transformation;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Самопроверка без клиента: строит стены, рамки и таблички в спавн-чанке, дёргает
 * {@link FrameMembrane} и ванильный {@code ItemFrame#interact} за {@code FakePlayer},
 * пишет результат в лог. Включается {@code -Dghostframe.selftest=1} или env {@code GHOSTFRAME_SELFTEST=1}.
 */
public final class SelfTest {
	/**
	 * Все клетки теста — внутри одного чанка (0,0), он же спавн-чанк: там секции сущностей
	 * гарантированно доступны, иначе {@code getEntitiesOfClass} не видит только что созданный
	 * дисплей. Шаг 3 оставляет место под соседей ({@code pos.west()}, {@code pos.east()}).
	 */
	private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);

	/**
	 * Клетки теста: сетка с шагом 3 внутри чанка (0,0), чтобы у каждой был свободный западный
	 * сосед (опора настенной таблички) и восточный. Мир {@code run/selftest} переживает прогоны,
	 * поэтому {@link #reset} перед тестами вычищает и клетки, и сущности.
	 */
	private static BlockPos cell(int index) {
		return ORIGIN.offset(1 + 3 * (index % 5), 0, 1 + 3 * (index / 5));
	}

	private static final int CELLS = 12;

	private SelfTest() {
	}

	private static boolean done;
	private static boolean prepared;
	private static int ticks;
	private static int failures;

	public static void onTick(MinecraftServer server) {
		if (done) {
			return;
		}
		ServerLevel level = server.overworld();
		if (!prepared) {
			// чанк форсим и грузим отдельным тиком: статус видимости сущностей применяется
			// на следующем тике, а дисплей, созданный в HIDDEN-секции, getEntitiesOfClass не видит
			level.setChunkForced(chunkX(), chunkZ(), true);
			level.getChunk(chunkX(), chunkZ());
			prepared = true;
			return;
		}
		if (++ticks < 20) {
			return;
		}
		done = true;
		run(server);
	}

	private static int chunkX() {
		return ORIGIN.getX() >> 4;
	}

	private static int chunkZ() {
		return ORIGIN.getZ() >> 4;
	}

	/**
	 * Мир самопроверки переживает прогоны: перед тестами убираем сущности и блоки прошлых
	 * запусков, иначе дисплеи копятся и проверки видят чужой текст.
	 */
	private static void reset(ServerLevel level) {
		AABB area = new AABB(ORIGIN.getX(), ORIGIN.getY() - 8.0, ORIGIN.getZ(),
				ORIGIN.getX() + 15.99, ORIGIN.getY() + 16.0, ORIGIN.getZ() + 15.99);
		level.getEntitiesOfClass(Entity.class, area).stream()
				.filter(entity -> !(entity instanceof Player))
				.forEach(Entity::discard);
		for (int i = 0; i < CELLS; i++) {
			BlockPos cell = cell(i);
			for (BlockPos pos : List.of(cell, cell.west(), cell.east(), cell.below())) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	public static void run(MinecraftServer server) {
		ServerLevel level = server.overworld();
		try {
			reset(level);
			testHideInsertRotateShow(level);
			testSneakKeepsVanilla(level);
			testCreativeDoesNotConsume(level);
			testGlowFrame(level);
			testOutOfReach(level);
			testSignGeometry(level);
			testSignHideRestore(level);
			testSignAnchorGuard(level);
			testStandingSign(level);
			testEmptySign(level);
			testNeighbourChest(level);
		} catch (Throwable t) {
			failures++;
			GhostFrame.LOGGER.error("SELFTEST упал", t);
		} finally {
			// чанк форсили только на время теста, в мире его оставлять нельзя
			level.setChunkForced(chunkX(), chunkZ(), false);
		}
		GhostFrame.LOGGER.info("SELFTEST ИТОГ: {} провалов", failures);
	}

	/** Прячем пустую рамку, кладём в неё предмет, вращаем, возвращаем рамку. */
	private static void testHideInsertRotateShow(ServerLevel level) {
		BlockPos pos = cell(0);
		ItemFrame frame = frame(level, pos, EntityTypes.ITEM_FRAME);
		FakePlayer player = player(level, Vec3.atCenterOf(pos), new ItemStack(Items.PHANTOM_MEMBRANE, 3), false, false);

		InteractionResult hide = FrameMembrane.onUseEntity(player, level, InteractionHand.MAIN_HAND, frame, hit(frame, pos));
		check("hide-empty", hide == InteractionResult.SUCCESS_SERVER && frame.isInvisible()
				&& player.getMainHandItem().getCount() == 2,
				"result=%s invisible=%s мембран=%d (ожидание SUCCESS_SERVER, true, 2)"
						.formatted(hide, frame.isInvisible(), player.getMainHandItem().getCount()));

		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND));
		InteractionResult put = frame.interact(player, InteractionHand.MAIN_HAND, Vec3.atCenterOf(pos));
		check("item-into-hidden", put == InteractionResult.SUCCESS && frame.getItem().is(Items.DIAMOND)
				&& frame.isInvisible() && player.getMainHandItem().isEmpty(),
				"result=%s предмет=%s invisible=%s в руке пусто=%s (ожидание SUCCESS, diamond, true, true)"
						.formatted(put, frame.getItem(), frame.isInvisible(), player.getMainHandItem().isEmpty()));

		int rotationBefore = frame.getRotation();
		frame.interact(player, InteractionHand.MAIN_HAND, Vec3.atCenterOf(pos));
		check("rotate-in-hidden", frame.getRotation() == (rotationBefore + 1) % ItemFrame.NUM_ROTATIONS
				&& frame.getItem().is(Items.DIAMOND) && frame.isInvisible(),
				"поворот %d->%d предмет=%s invisible=%s (ожидание +1, diamond, true)"
						.formatted(rotationBefore, frame.getRotation(), frame.getItem(), frame.isInvisible()));

		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PHANTOM_MEMBRANE, 2));
		InteractionResult show = FrameMembrane.onUseEntity(player, level, InteractionHand.MAIN_HAND, frame, hit(frame, pos));
		check("show-again", show == InteractionResult.SUCCESS_SERVER && !frame.isInvisible()
				&& frame.getItem().is(Items.DIAMOND) && player.getMainHandItem().getCount() == 1,
				"result=%s invisible=%s предмет=%s мембран=%d (ожидание SUCCESS_SERVER, false, diamond, 1)"
						.formatted(show, frame.isInvisible(), frame.getItem(), player.getMainHandItem().getCount()));
	}

	/** Shift + мембрана: мод молчит, ванильный клик кладёт мембрану в рамку как предмет. */
	private static void testSneakKeepsVanilla(ServerLevel level) {
		BlockPos pos = cell(1);
		ItemFrame frame = frame(level, pos, EntityTypes.ITEM_FRAME);
		FakePlayer player = player(level, Vec3.atCenterOf(pos), new ItemStack(Items.PHANTOM_MEMBRANE, 2), true, false);

		InteractionResult result = FrameMembrane.onUseEntity(player, level, InteractionHand.MAIN_HAND, frame, hit(frame, pos));
		boolean invisibleAfterClick = frame.isInvisible();
		int membranesAfterClick = player.getMainHandItem().getCount();
		boolean untouched = result == InteractionResult.PASS && !invisibleAfterClick && membranesAfterClick == 2;
		frame.interact(player, InteractionHand.MAIN_HAND, Vec3.atCenterOf(pos));
		boolean inserted = frame.getItem().is(Items.PHANTOM_MEMBRANE) && !frame.isInvisible();
		check("sneak-keeps-vanilla", untouched && inserted,
				("result=%s invisible=%s мембран=%d, после ванильного клика предмет=%s invisible=%s "
						+ "(ожидание PASS, false, 2, phantom_membrane, false)")
						.formatted(result, invisibleAfterClick, membranesAfterClick, frame.getItem(), frame.isInvisible()));
	}

	/** В креативе мембрана не тратится. */
	private static void testCreativeDoesNotConsume(ServerLevel level) {
		BlockPos pos = cell(2);
		ItemFrame frame = frame(level, pos, EntityTypes.ITEM_FRAME);
		FakePlayer player = player(level, Vec3.atCenterOf(pos), new ItemStack(Items.PHANTOM_MEMBRANE, 1), false, true);

		InteractionResult result = FrameMembrane.onUseEntity(player, level, InteractionHand.MAIN_HAND, frame, hit(frame, pos));
		check("creative-no-consume", result == InteractionResult.SUCCESS_SERVER && frame.isInvisible()
				&& player.getMainHandItem().getCount() == 1,
				"result=%s invisible=%s мембран=%d (ожидание SUCCESS_SERVER, true, 1)"
						.formatted(result, frame.isInvisible(), player.getMainHandItem().getCount()));
	}

	/** Светящаяся рамка — та же сущность, флаг тот же. */
	private static void testGlowFrame(ServerLevel level) {
		BlockPos pos = cell(3);
		ItemFrame frame = frame(level, pos, EntityTypes.GLOW_ITEM_FRAME);
		FakePlayer player = player(level, Vec3.atCenterOf(pos), new ItemStack(Items.PHANTOM_MEMBRANE, 2), false, false);

		InteractionResult result = FrameMembrane.onUseEntity(player, level, InteractionHand.MAIN_HAND, frame, hit(frame, pos));
		check("glow-frame", result == InteractionResult.SUCCESS_SERVER && frame.isInvisible()
				&& player.getMainHandItem().getCount() == 1,
				"result=%s invisible=%s мембран=%d (ожидание SUCCESS_SERVER, true, 1)"
						.formatted(result, frame.isInvisible(), player.getMainHandItem().getCount()));
	}

	/** Издалека клик не проходит: отмена события обходит ванильную проверку дистанции. */
	private static void testOutOfReach(ServerLevel level) {
		BlockPos pos = cell(4);
		ItemFrame frame = frame(level, pos, EntityTypes.ITEM_FRAME);
		FakePlayer player = player(level, Vec3.atCenterOf(pos).add(20, 0, 0), new ItemStack(Items.PHANTOM_MEMBRANE, 2), false, false);

		InteractionResult result = FrameMembrane.onUseEntity(player, level, InteractionHand.MAIN_HAND, frame, hit(frame, pos));
		check("out-of-reach", result == InteractionResult.PASS && !frame.isInvisible()
				&& player.getMainHandItem().getCount() == 2,
				"result=%s invisible=%s мембран=%d (ожидание PASS, false, 2)"
						.formatted(result, frame.isInvisible(), player.getMainHandItem().getCount()));
	}

	/** Геометрия считается по формулам ванильных рендереров; сверяем с ручным расчётом. */
	private static void testSignGeometry(ServerLevel level) {
		BlockPos pos = cell(5);

		BlockState wall = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST);
		float wallAngle = SignGhost.signAngle(wall);
		Vec3 wallFront = SignGhost.displayPos(pos, wall, wallAngle, true);
		Vec3 wallBack = SignGhost.displayPos(pos, wall, wallAngle, false);
		boolean wallOk = near(wallFront, pos, 0.109167, 0.322917, 0.5)
				&& near(wallBack, pos, 0.015833, 0.322917, 0.5)
				&& sameYaw(SignGhost.displayYaw(wallAngle, true), 270.0F)
				&& sameYaw(SignGhost.displayYaw(wallAngle, false), 90.0F);

		BlockState ground = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0);
		Vec3 groundFront = SignGhost.displayPos(pos, ground, SignGhost.signAngle(ground), true);
		Vec3 groundBack = SignGhost.displayPos(pos, ground, SignGhost.signAngle(ground), false);
		boolean groundOk = near(groundFront, pos, 0.5, 0.635417, 0.546667)
				&& near(groundBack, pos, 0.5, 0.635417, 0.453333);

		Component[] lines = {Component.literal("Привет"), Component.literal("Мир"), Component.empty(), Component.empty()};
		String text = SignGhost.displayText(new SignText(lines, lines, DyeColor.LIME, false)).getString();
		boolean textOk = text.equals("Привет\nМир\n\n");

		// обычные чернила — 40 % красителя, светящиеся — полный цвет (AbstractSignRenderer.getDarkColor)
		int ink = SignGhost.displayText(new SignText(lines, lines, DyeColor.LIME, false))
				.getSiblings().get(0).getStyle().getColor().getValue();
		int glow = SignGhost.displayText(new SignText(lines, lines, DyeColor.LIME, true))
				.getSiblings().get(0).getStyle().getColor().getValue();
		// цвет в TextColor без альфы, у DyeColor он с альфой — сравниваем только RGB
		boolean colorOk = (ink & 0xFFFFFF) == (ARGB.scaleRGB(DyeColor.LIME.getTextColor(), 0.4F) & 0xFFFFFF)
				&& (glow & 0xFFFFFF) == (DyeColor.LIME.getTextColor() & 0xFFFFFF);

		check("sign-geometry", wallOk && groundOk && textOk && colorOk,
				("стена=%s обратная=%s земля=%s/%s текст=%s цвет=%s/%s "
						+ "(ожидание 0.1092/0.3229/0.5 + yaw 270, 0.0158/0.3229/0.5 + yaw 90, "
						+ "0.5/0.6354/0.5467 и 0.5/0.6354/0.4533, «Привет\\nМир\\n\\n», 0.4·краситель/краситель)")
						.formatted(wallFront, wallBack, groundFront, groundBack, text.replace("\n", "\\n"),
								Integer.toHexString(ink), Integer.toHexString(glow)));
	}

	/** Скрытие настенной таблички: маркер вместо блока, дисплеи на две стороны, возврат по удару. */
	private static void testSignHideRestore(ServerLevel level) {
		BlockPos pos = cell(6);
		BlockState signState = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST);
		if (!(placeSign(level, pos, signState) instanceof SignBlockEntity sign)) {
			check("sign-hide", false, "нет block entity таблички");
			return;
		}
		sign.setText(signText("Привет", "Мир"), true);
		sign.setText(signText("Сзади", ""), false);
		sign.setWaxed(true);

		// обычный ПКМ по табличке открывает редактор — прятать должен только Shift+ПКМ
		FakePlayer player = player(level, Vec3.atCenterOf(pos).add(0.6, 0, 0),
				new ItemStack(Items.PHANTOM_MEMBRANE, 3), false, false);
		InteractionResult plain = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, blockHit(pos));
		check("sign-needs-shift", plain == InteractionResult.PASS
						&& level.getBlockState(pos).is(Blocks.OAK_WALL_SIGN)
						&& player.getMainHandItem().getCount() == 3
						&& signDisplays(level, pos).isEmpty(),
				"result=%s блок=%s мембран=%d дисплеев=%d (ожидание PASS, oak_wall_sign, 3, 0)"
						.formatted(plain, level.getBlockState(pos).getBlock(),
								player.getMainHandItem().getCount(), signDisplays(level, pos).size()));

		player.setShiftKeyDown(true);
		InteractionResult hidden = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, blockHit(pos));
		List<Display.TextDisplay> displays = signDisplays(level, pos);
		check("sign-hide", hidden == InteractionResult.SUCCESS_SERVER
						&& level.getBlockState(pos).is(Blocks.STRUCTURE_VOID)
						&& player.getMainHandItem().getCount() == 2
						&& displays.size() == 2,
				"result=%s маркер=%s мембран=%d дисплеев=%d (ожидание SUCCESS_SERVER, true, 2, 2)"
						.formatted(hidden, level.getBlockState(pos).is(Blocks.STRUCTURE_VOID),
								player.getMainHandItem().getCount(), displays.size()));

		boolean displayOk = false;
		boolean placementOk = false;
		String displayDetail = "нет дисплея";
		if (!displays.isEmpty()) {
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			displays.get(0).saveWithoutId(out);
			CompoundTag tag = out.buildResult();
			int background = tag.getIntOr("background", -1);
			String billboard = tag.getStringOr("billboard", "");
			int lineWidth = tag.getIntOr("line_width", -1);
			boolean text = tag.toString().contains("Привет");
			// масштаб шрифта — главная визуальная константа; литерал, а не SignGhost.DISPLAY_SCALE, иначе тест тавтологичен
			ValueInput tagInput = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag);
			boolean scaleOk = tagInput.read("transformation", Transformation.EXTENDED_CODEC)
					.map(transformation -> Math.abs(transformation.scale().x() - 0.4166667F) < 1.0E-4F)
					.orElse(false);
			displayOk = background == 0 && billboard.equals("fixed") && lineWidth == SignGhost.LINE_WIDTH && text && scaleOk;
			displayDetail = "background=%d billboard=%s line_width=%d текст=%s масштаб=%s (ожидание 0, fixed, %d, true, %.4f)"
					.formatted(background, billboard, lineWidth, text, scaleOk, SignGhost.LINE_WIDTH, SignGhost.DISPLAY_SCALE);

			float angle = SignGhost.signAngle(signState);
			Vec3 front = SignGhost.displayPos(pos, signState, angle, true);
			Vec3 back = SignGhost.displayPos(pos, signState, angle, false);
			boolean frontSeen = false;
			boolean backSeen = false;
			for (Display.TextDisplay display : displays) {
				TagValueOutput state = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
				display.saveWithoutId(state);
				ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), state.buildResult());
				Vec3 at = in.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO);
				Vec2 rot = in.read("Rotation", Vec2.CODEC).orElse(Vec2.ZERO);
				frontSeen |= at.distanceToSqr(front) < 1.0E-8 && sameYaw(rot.x, SignGhost.displayYaw(angle, true));
				backSeen |= at.distanceToSqr(back) < 1.0E-8 && sameYaw(rot.x, SignGhost.displayYaw(angle, false));
			}
			placementOk = frontSeen && backSeen;
		}
		check("sign-display-nbt", displayOk, displayDetail);
		check("sign-display-sides", placementOk, "лицо и обратная сторона на своих местах=" + placementOk);

		boolean restored = false;
		boolean breakAllowed = SignGhost.onBlockBreak(level, player, pos, level.getBlockState(pos));
		if (level.getBlockEntity(pos) instanceof SignBlockEntity back) {
			restored = back.getFrontText().getMessage(0, false).getString().equals("Привет")
					&& back.getFrontText().getMessage(1, false).getString().equals("Мир")
					&& back.getBackText().getMessage(0, false).getString().equals("Сзади")
					&& back.getFrontText().getColor() == DyeColor.LIME
					&& back.getFrontText().hasGlowingText()
					&& back.isWaxed();
		}
		check("sign-restore", !breakAllowed && level.getBlockState(pos).is(Blocks.OAK_WALL_SIGN) && restored
						&& signDisplays(level, pos).isEmpty(),
				"ломать разрешено=%s блок=%s текст/цвет/воск=%s дисплеев=%d (ожидание false, oak_wall_sign, true, 0)"
						.formatted(breakAllowed, level.getBlockState(pos).getBlock(), restored,
								signDisplays(level, pos).size()));
	}

	/** Маркер не даёт поставить в себя блок, ПКМ мембраной возвращает табличку, чужой void ломается как обычно. */
	private static void testSignAnchorGuard(ServerLevel level) {
		BlockPos pos = cell(7);
		BlockState signState = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST);
		if (placeSign(level, pos, signState) instanceof SignBlockEntity sign) {
			sign.setText(signText("Маркер", ""), true);
		}

		FakePlayer player = player(level, Vec3.atCenterOf(pos), new ItemStack(Items.PHANTOM_MEMBRANE, 2), true, false);
		SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, blockHit(pos));

		player.setShiftKeyDown(false);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 5));
		InteractionResult guarded = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, blockHit(pos));
		boolean markerKept = level.getBlockState(pos).is(Blocks.STRUCTURE_VOID);
		int stoneCount = player.getMainHandItem().getCount();
		boolean anchorKept = guarded == InteractionResult.SUCCESS_SERVER
				&& markerKept
				&& stoneCount == 5;

		// клик по стене рядом тоже не должен ставить блок в клетку маркера (structure_void заменяемый)
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 4));
		InteractionResult neighbour = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(pos.west()), Direction.EAST, pos.west(), false));
		boolean neighbourKept = neighbour == InteractionResult.SUCCESS_SERVER
				&& level.getBlockState(pos).is(Blocks.STRUCTURE_VOID)
				&& player.getMainHandItem().getCount() == 4;
		boolean neighbourMarker = level.getBlockState(pos).is(Blocks.STRUCTURE_VOID);
		int neighbourStone = player.getMainHandItem().getCount();

		// без «ставящего блок» предмета соседняя стена остаётся обычной: сундук/кнопка рядом должны работать
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK, 1));
		InteractionResult stick = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(pos.west()), Direction.EAST, pos.west(), false));
		boolean stickPasses = stick == InteractionResult.PASS;

		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PHANTOM_MEMBRANE, 5));
		InteractionResult clickRestore = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, blockHit(pos));
		boolean clickRestored = clickRestore == InteractionResult.SUCCESS_SERVER
				&& level.getBlockState(pos).is(Blocks.OAK_WALL_SIGN)
				&& signDisplays(level, pos).isEmpty()
				&& player.getMainHandItem().getCount() == 5;

		BlockPos plain = cell(8);
		level.setBlock(plain, Blocks.STRUCTURE_VOID.defaultBlockState(), 3);
		boolean plainBreakAllowed = SignGhost.onBlockBreak(level, player, plain, level.getBlockState(plain));

		check("sign-anchor-guard", anchorKept && neighbourKept && stickPasses && clickRestored && plainBreakAllowed,
				("клик блоком: result=%s маркер=%s камень=%d | соседняя стена: result=%s маркер=%s камень=%d "
						+ "| соседняя стена палкой: %s | ПКМ мембраной: result=%s блок=%s дисплеев=%d | чужой void ломается=%s "
						+ "(ожидание SUCCESS_SERVER, true, 5 | SUCCESS_SERVER, true, 4 | PASS | SUCCESS_SERVER, oak_wall_sign, 0 | true)")
						.formatted(guarded, markerKept, stoneCount, neighbour, neighbourMarker,
								neighbourStone, stick, clickRestore, level.getBlockState(pos).getBlock(),
								signDisplays(level, pos).size(), plainBreakAllowed));
	}

	/**
	 * Стоячая табличка: угол берётся из {@code ROTATION} (сегмент 4 = 90°), а не из FACING.
	 * Заодно проверяется квадрант поворота — у настенной EAST-таблички sin/cos вырождены.
	 */
	private static void testStandingSign(ServerLevel level) {
		BlockPos pos = cell(9);
		level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		BlockState signState = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 4);
		level.setBlock(pos, signState, 3);
		if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
			check("standing-sign", false, "нет block entity стоячей таблички");
			return;
		}
		sign.setText(signText("Стоя", "чая"), true);

		FakePlayer player = player(level, Vec3.atCenterOf(pos).add(0, 0, 0.6),
				new ItemStack(Items.PHANTOM_MEMBRANE, 2), true, false);
		InteractionResult hidden = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, blockHit(pos));
		List<Display.TextDisplay> displays = signDisplays(level, pos);

		boolean placed = false;
		Vec3 at = Vec3.ZERO;
		float yaw = -1.0F;
		if (displays.size() == 1) {
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			displays.get(0).saveWithoutId(out);
			ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), out.buildResult());
			at = in.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO);
			yaw = in.read("Rotation", Vec2.CODEC).map(rot -> rot.x).orElse(-1.0F);
			// ROTATION 4 -> 90°: sin(-90°) = -1, cos(-90°) = 0, z смещения 0.046667
			placed = near(at, pos, 0.453333, 0.635417, 0.5) && sameYaw(yaw, 90.0F);
		}

		boolean breakAllowed = SignGhost.onBlockBreak(level, player, pos, level.getBlockState(pos));
		boolean restored = level.getBlockEntity(pos) instanceof SignBlockEntity back
				&& back.getFrontText().getMessage(0, false).getString().equals("Стоя");

		check("standing-sign", hidden == InteractionResult.SUCCESS_SERVER && placed && !breakAllowed && restored
						&& signDisplays(level, pos).isEmpty(),
				("result=%s дисплеев=%d позиция=%s yaw=%.1f возврат=%s "
						+ "(ожидание SUCCESS_SERVER, 1, 0.4533/0.6354/0.5, 90.0, true)")
						.formatted(hidden, displays.size(), at, yaw, restored));
	}

	/** Пустую табличку мод не прячет: невидимый маркер без текста — ловушка без подсказок. */
	private static void testEmptySign(ServerLevel level) {
		BlockPos pos = cell(10);
		BlockState signState = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST);
		placeSign(level, pos, signState);

		FakePlayer player = player(level, Vec3.atCenterOf(pos), new ItemStack(Items.PHANTOM_MEMBRANE, 2), true, false);
		InteractionResult result = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, blockHit(pos));

		check("sign-empty-refused", result == InteractionResult.PASS
						&& level.getBlockState(pos).is(Blocks.OAK_WALL_SIGN)
						&& player.getMainHandItem().getCount() == 2
						&& signDisplays(level, pos).isEmpty(),
				"result=%s блок=%s мембран=%d дисплеев=%d (ожидание PASS, oak_wall_sign, 2, 0)"
						.formatted(result, level.getBlockState(pos).getBlock(),
								player.getMainHandItem().getCount(), signDisplays(level, pos).size()));
	}

	/**
	 * Сундук рядом с маркером: клик по нему с блоком в руке должен открывать сундук (у блока есть
	 * BlockEntity, ваниль отдаёт {@code TRY_WITH_EMPTY_HAND}), а вот Shift+блок — ставить блок,
	 * и только там щит маркера обязан вмешаться.
	 */
	private static void testNeighbourChest(ServerLevel level) {
		BlockPos pos = cell(11);
		BlockState signState = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST);
		if (placeSign(level, pos, signState) instanceof SignBlockEntity sign) {
			sign.setText(signText("Сундук", ""), true);
		}
		FakePlayer player = player(level, Vec3.atCenterOf(pos), new ItemStack(Items.PHANTOM_MEMBRANE, 2), true, false);
		SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, blockHit(pos));
		boolean hidden = level.getBlockState(pos).is(Blocks.STRUCTURE_VOID);
		level.setBlock(pos.west(), Blocks.CHEST.defaultBlockState(), 3);

		BlockHitResult chestHit = new BlockHitResult(Vec3.atCenterOf(pos.west()), Direction.EAST, pos.west(), false);

		player.setShiftKeyDown(false);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 3));
		InteractionResult plain = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, chestHit);
		boolean chestOpens = plain == InteractionResult.PASS
				&& level.getBlockState(pos).is(Blocks.STRUCTURE_VOID)
				&& player.getMainHandItem().getCount() == 3;

		player.setShiftKeyDown(true);
		InteractionResult sneaking = SignGhost.onUseBlock(player, level, InteractionHand.MAIN_HAND, chestHit);
		boolean guarded = sneaking == InteractionResult.SUCCESS_SERVER
				&& level.getBlockState(pos).is(Blocks.STRUCTURE_VOID)
				&& player.getMainHandItem().getCount() == 3;

		check("sign-neighbour-chest", hidden && chestOpens && guarded,
				("маркер=%s | клик камнем по сундуку: result=%s маркер=%s камень=%d | Shift+камень: result=%s камень=%d "
						+ "(ожидание true | PASS, true, 3 | SUCCESS_SERVER, 3)")
						.formatted(hidden, plain, level.getBlockState(pos).is(Blocks.STRUCTURE_VOID),
								player.getMainHandItem().getCount(), sneaking,
								player.getMainHandItem().getCount()));
	}

	private static BlockEntity placeSign(ServerLevel level, BlockPos pos, BlockState signState) {
		level.setBlock(pos.west(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(pos, signState, 3);
		return level.getBlockEntity(pos);
	}

	private static SignText signText(String first, String second) {
		Component[] lines = {
				Component.literal(first), Component.literal(second), Component.empty(), Component.empty()
		};
		return new SignText(lines, lines, DyeColor.LIME, true);
	}

	private static BlockHitResult blockHit(BlockPos pos) {
		return new BlockHitResult(Vec3.atCenterOf(pos), Direction.EAST, pos, false);
	}

	private static List<Display.TextDisplay> signDisplays(ServerLevel level, BlockPos pos) {
		return level.getEntitiesOfClass(Display.TextDisplay.class, new AABB(pos).inflate(3.0),
				display -> display.entityTags().contains(SignGhost.TAG_SIGN));
	}

	private static boolean near(Vec3 actual, BlockPos pos, double dx, double dy, double dz) {
		return Math.abs(actual.x - (pos.getX() + dx)) < 1.0E-4
				&& Math.abs(actual.y - (pos.getY() + dy)) < 1.0E-4
				&& Math.abs(actual.z - (pos.getZ() + dz)) < 1.0E-4;
	}

	private static boolean sameYaw(float a, float b) {
		return Math.abs(Mth.wrapDegrees(a - b)) < 1.0E-4F;
	}

	private static ItemFrame frame(ServerLevel level, BlockPos pos, EntityType<? extends ItemFrame> type) {
		level.setBlock(pos.west(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), 3);
		ItemFrame frame = new ItemFrame(type, level, pos, Direction.EAST);
		level.addFreshEntity(frame);
		return frame;
	}

	private static FakePlayer player(ServerLevel level, Vec3 pos, ItemStack held, boolean sneaking, boolean creative) {
		FakePlayer player = FakePlayer.get(level);
		player.setPos(pos.x, pos.y, pos.z);
		player.setShiftKeyDown(sneaking);
		player.getAbilities().instabuild = creative;
		player.onUpdateAbilities();
		player.getInventory().clearContent();
		player.setItemInHand(InteractionHand.MAIN_HAND, held);
		return player;
	}

	private static EntityHitResult hit(ItemFrame frame, BlockPos pos) {
		return new EntityHitResult(frame, Vec3.atCenterOf(pos));
	}

	private static void check(String name, boolean ok, String detail) {
		if (!ok) {
			failures++;
		}
		GhostFrame.LOGGER.info("SELFTEST {}: {} {}", name, ok ? "PASS" : "FAIL", detail);
	}
}
