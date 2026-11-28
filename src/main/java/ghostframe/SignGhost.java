package ghostframe;

import com.mojang.math.Transformation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PlainSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Невидимая табличка: блок таблички заменяется ванильным {@code structure_void} (невидим, без коллизии,
 * мгновенно ломается), а текст переносится в {@code text_display} — прозрачный фон, те же строки, цвет
 * и размер, что у таблички.
 * <p>
 * Геометрия не на глаз, а из ванильных рендереров. Табличка: {@code StandingSignRenderer.textTransformation}
 * строит {@code T(0.5,0.5,0.5)·R(-angle)·T(offset)·S(1/96,-1/96,1/96)}, где для настенной таблички
 * {@code offset} дополнительно сдвинут на {@code (0,-0.3125,-0.4375)}, а у обратной стороны перед
 * {@code TEXT_OFFSET} стоит поворот на 180° (поэтому z смещения у неё зеркальный). Дисплей:
 * {@code DisplayRenderer.TextDisplayRenderer.submitInner} даёт ту же локальную систему
 * {@code R(180°)·S(-0.025)} = {@code S(0.025,-0.025,0.025)}, но блок текста у него стоит НАД началом
 * сущности ({@code y = s·(h - y_px)}, {@code h = строк·10 - 1}), а не вокруг него. Отсюда множитель
 * {@code (1/96)/0.025 = 0.41667} и сдвиг начала вниз на {@code (20 - 39)/96} — чтобы строки легли
 * пиксель в пиксель.
 * <p>
 * Убирается ударом по маркеру: табличка возвращается на место вместе с NBT (текст, краситель, светящиеся
 * чернила, воск, обе стороны), дисплеи удаляются.
 */
public final class SignGhost {
	public static final String TAG_SIGN = "ghostframe:sign";
	private static final String TAG_ANCHOR = "ghostframe:anchor:";
	private static final String TAG_DATA = "ghostframe:data:";

	/** Один пиксель шрифта таблички — {@code StandingSignRenderer.textTransformation}. */
	static final float SIGN_TEXT_SCALE = 0.010416667F;
	/** Один пиксель шрифта {@code text_display} — {@code DisplayRenderer.TextDisplayRenderer}. */
	static final float DISPLAY_TEXT_SCALE = 0.025F;
	static final float DISPLAY_SCALE = SIGN_TEXT_SCALE / DISPLAY_TEXT_SCALE;
	/** {@code StandingSignRenderer.TEXT_OFFSET}. */
	static final float TEXT_OFFSET_Y = 0.33333334F;
	static final float TEXT_OFFSET_Z = 0.046666667F;
	/** Сдвиг настенной таблички: доска прижата к стене, текст сидит на ней же. */
	static final float WALL_OFFSET_Y = -0.3125F;
	/**
	 * Настенная табличка превращается в граффити: доски больше нет, поэтому текст ставится к стене
	 * вплотную — на {@code GRAFFITI_DEPTH} от её плоскости. Ванильные смещения ({@code -0.4375} доски
	 * плюс {@code 0.046667} текста) дают 0.109 от стены: это правильное место для текста НА доске, но
	 * без доски он висит в воздухе. Сантиметр — чтобы глифы не мерцали с текстурой блока (у текста
	 * к тому же {@code POLYGON_OFFSET}).
	 */
	static final double GRAFFITI_DEPTH = 0.012;
	/** {@code SignBlockEntity.getTextLineHeight()}. */
	static final int LINE_HEIGHT_PX = 10;
	/** Блок текста таблички: 4 строки, отрисованы вокруг точки трансформации. */
	static final int SIGN_TEXT_HEIGHT_PX = 4 * LINE_HEIGHT_PX;
	/**
	 * Блок текста дисплея: {@code строк*10 - 1} пикселей, низ блока — в точке сущности. Хвостовые пустые
	 * строки при этом сохраняются: {@code StringSplitter.splitLines} в конце вызывает
	 * {@code output.accept(FormattedText.EMPTY)} при {@code forceNewLine}, так что «A\nB\n\n» — это
	 * ровно 4 строки, как у таблички.
	 */
	static final int DISPLAY_TEXT_HEIGHT_PX = 4 * LINE_HEIGHT_PX - 1;
	/** Насколько точка дисплея ниже центра текста таблички: {@code (20 - 39) px}. */
	static final double TEXT_ANCHOR_SHIFT = (SIGN_TEXT_HEIGHT_PX / 2 - DISPLAY_TEXT_HEIGHT_PX) * (double) SIGN_TEXT_SCALE;
	/**
	 * Ширина строки дисплея. У таблички {@code getMaxTextLineWidth() = 90}, но она длинную строку
	 * обрезает, а дисплей переносит — и от переноса блок текста уезжает вверх (низ блока привязан
	 * к точке сущности). Поэтому переноса нет вовсе: строка до ~250 символов торчит за края, зато
	 * все четыре строки стоят на своих местах. Более длинную строку перенесёт, и текст поедет.
	 */
	static final int LINE_WIDTH = 1000;
	/** Ванильный {@code handleUseItemOn} проверяет дистанцию до блока сам, но при отмене события эта проверка пропускается. */
	static final double BLOCK_INTERACTION_BUFFER = 1.0;

	private SignGhost() {
	}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		// событие приходит раньше ванильных проверок; getBlockState на незагруженном чанке его бы подгрузил
		if (!level.isLoaded(pos)) {
			return InteractionResult.PASS;
		}
		BlockState state = level.getBlockState(pos);

		// клик по соседнему блоку ставит предмет В клетку маркера (structure_void заменяемый) — и текст осиротеет.
		// Сундук/бочка/печь отдают TRY_WITH_EMPTY_HAND и открываются вместо установки, поэтому их не трогаем;
		// а вот Shift+блок ваниль ставит всегда (useItemOn пропускается) — там щит нужен для любого блока.
		if (!state.is(Blocks.STRUCTURE_VOID) && placesBlock(player.getItemInHand(hand))
				&& (player.isSecondaryUseActive() || !state.hasBlockEntity())
				&& isGhostMarker(level, pos.relative(hit.getDirection()))) {
			return InteractionResult.SUCCESS_SERVER;
		}

		if (state.is(Blocks.STRUCTURE_VOID) && isGhost(level, pos)) {
			if (level.isClientSide()) {
				// оживает только при environment: "*" — в server-моде клиент этот код не грузит
				return InteractionResult.SUCCESS;
			}
			// structure_void заменяемый: без этого клик блоком снёс бы маркер и оставил текст сиротой
			if (!isMembrane(player, hand)) {
				return InteractionResult.SUCCESS_SERVER;
			}
			// отмена события обходит ванильные mayInteract/спектатора/дистанцию — повторяем их руками
			if (player.isSpectator() || !level.mayInteract(player, pos)
					|| !player.isWithinBlockInteractionRange(pos, BLOCK_INTERACTION_BUFFER)) {
				return InteractionResult.PASS;
			}
			RestoreResult restored = restore((ServerLevel) level, pos);
			if (restored == RestoreResult.DONE) {
				player.swing(hand, true);
				return InteractionResult.SUCCESS_SERVER;
			}
			if (restored == RestoreResult.BAD_DATA) {
				// висящий текст без данных хуже, чем потеря текста: убираем и отдаём клетку ванили
				GhostFrame.LOGGER.warn("GhostFrame: битые данные невидимой таблички на {} — текст удалён", pos);
				discard((ServerLevel) level, pos);
			}
			// BLOCKED: ставить блок не дали, дисплеи и данные остаются на месте
			return InteractionResult.SUCCESS_SERVER;
		}

		if (!(state.getBlock() instanceof PlainSignBlock)) {
			// висячие таблички рисует другой рендерер с другими отступами — их не трогаем
			return InteractionResult.PASS;
		}
		// обычный ПКМ по табличке открывает редактор, поэтому прячет только Shift+ПКМ
		if (!isMembrane(player, hand) || !player.isSecondaryUseActive()) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			// оживает только при environment: "*" — в server-моде клиент этот код не грузит
			return InteractionResult.SUCCESS;
		}
		if (player.isSpectator() || !level.mayInteract(player, pos)
				|| !player.isWithinBlockInteractionRange(pos, BLOCK_INTERACTION_BUFFER)) {
			return InteractionResult.PASS;
		}
		if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
			return InteractionResult.PASS;
		}
		return hide((ServerLevel) level, pos, state, sign, player, hand)
				? InteractionResult.SUCCESS_SERVER
				: InteractionResult.PASS;
	}

	/**
	 * Ломать маркер — значит вернуть табличку. {@code false} отменяет ванильное разрушение блока:
	 * и когда табличка вернулась, и когда вернуть её не дали (данные нельзя терять).
	 */
	public static boolean onBlockBreak(Level level, Player player, BlockPos pos, BlockState state) {
		if (!state.is(Blocks.STRUCTURE_VOID) || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
			return true;
		}
		if (find(serverLevel, pos).isEmpty()) {
			// чужой structure_void — обычный блок
			return true;
		}
		RestoreResult restored = restore(serverLevel, pos);
		if (restored == RestoreResult.DONE) {
			return false;
		}
		if (restored == RestoreResult.BAD_DATA) {
			GhostFrame.LOGGER.warn("GhostFrame: битые данные невидимой таблички на {} — текст удалён", pos);
			discard(serverLevel, pos);
			return true;
		}
		GhostFrame.LOGGER.warn("GhostFrame: {} не дал вернуть табличку на {}", player.getName().getString(), pos);
		return false;
	}

	private static boolean hide(ServerLevel level, BlockPos pos, BlockState state, SignBlockEntity sign, Player player,
			InteractionHand hand) {
		SignText front = sign.getFrontText();
		SignText back = sign.getBackText();
		// пустая табличка дала бы невидимую клетку без единой подсказки и без текста — прятать нечего
		if (isEmpty(front) && isEmpty(back)) {
			return false;
		}
		String dataTag = encode(level, state, sign);
		if (dataTag == null) {
			return false;
		}
		String anchorTag = anchorTag(pos);
		// у настенной таблички обратная сторона смотрит в стену: ваниль ставит её текст почти в
		// плоскость стены (−0.4375 доски − 0.046667 текста = 0.0158 от неё), а без доски он совпал бы
		// с местом лицевой — второй дисплей не нужен. Пустую сторону не создаём вовсе: иначе
		// настенная табличка с текстом только сзади спряталась бы в полностью невидимую клетку
		boolean graffiti = state.getBlock() instanceof WallSignBlock;
		boolean hasFront = !isEmpty(front);
		boolean hasBack = !graffiti && !isEmpty(back);

		// сначала создаём дисплеи: если что-то не так, табличка должна остаться на месте
		Display.TextDisplay frontDisplay = hasFront ? create(level, pos, state, front, true, anchorTag, dataTag) : null;
		Display.TextDisplay backDisplay = hasBack ? create(level, pos, state, back, false, anchorTag, dataTag) : null;
		if (!hasFront && !hasBack) {
			// показывать нечего: маркер без текста — невидимая занятая клетка
			return false;
		}
		if ((hasFront && frontDisplay == null) || (hasBack && backDisplay == null)) {
			// половина текста без второй стороны — хуже, чем не трогать табличку
			GhostFrame.LOGGER.warn("GhostFrame: не удалось создать дисплей для таблички на {}", pos);
			return false;
		}

		// дисплеи от прежней таблички в этой клетке (маркер убили извне) убираем, иначе текст удвоится
		discard(level, pos);
		if (!level.setBlock(pos, Blocks.STRUCTURE_VOID.defaultBlockState(), 3)) {
			GhostFrame.LOGGER.warn("GhostFrame: установку маркера отменили на {}", pos);
			return false;
		}
		// пустая сторона — null: добавлять её вслепую нельзя, это NPE уже после установки маркера
		if (frontDisplay != null) {
			level.addFreshEntity(frontDisplay);
		}
		if (backDisplay != null) {
			level.addFreshEntity(backDisplay);
		}

		level.playSound(null, pos, SoundEvents.PHANTOM_FLAP, SoundSource.BLOCKS, 0.8F, 0.9F);
		player.getItemInHand(hand).consume(1, player);
		player.swing(hand, true);
		return true;
	}

	private static Display.@Nullable TextDisplay create(ServerLevel level, BlockPos pos, BlockState state, SignText text,
			boolean front, String anchorTag, String dataTag) {
		Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		if (display == null) {
			return null;
		}
		float angle = signAngle(state);
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		out.store("Pos", Vec3.CODEC, displayPos(pos, state, angle, front));
		out.store("Rotation", Vec2.CODEC, new Vec2(displayYaw(angle, front), 0.0F));
		out.store("text", ComponentSerialization.CODEC, displayText(text));
		out.putInt("background", 0);
		out.putInt("line_width", LINE_WIDTH);
		out.putByte("text_opacity", (byte) -1);
		out.store("billboard", Display.BillboardConstraints.CODEC, Display.BillboardConstraints.FIXED);
		if (text.hasGlowingText()) {
			// светящиеся чернила у таблички горят в темноте (полная яркость), дисплею для этого нужен
			// собственный brightness override — иначе граффити в тёмном углу просто чёрное
			out.store("brightness", Brightness.CODEC, Brightness.FULL_BRIGHT);
		}
		out.store("transformation", Transformation.EXTENDED_CODEC,
				new Transformation(null, null, new Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE), null));

		display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), out.buildResult()));
		// Entity#load очищает Tags, поэтому вешаем их после загрузки
		display.addTag(TAG_SIGN);
		display.addTag(anchorTag);
		display.addTag(dataTag);
		return display;
	}

	private static String encode(ServerLevel level, BlockState state, SignBlockEntity sign) {
		try {
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			out.store("state", BlockState.CODEC, state);
			out.store("be", CompoundTag.CODEC, sign.saveWithFullMetadata(level.registryAccess()));
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			NbtIo.write(out.buildResult(), new DataOutputStream(bytes));
			return TAG_DATA + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
		} catch (IOException | RuntimeException e) {
			GhostFrame.LOGGER.warn("GhostFrame: не удалось сохранить данные таблички на {}", sign.getBlockPos(), e);
			return null;
		}
	}

	/** Чем кончилась попытка вернуть табличку. */
	private enum RestoreResult {
		/** Табличка на месте, дисплеи удалены. */
		DONE,
		/** Данные нечитаемы — вернуть нечего. */
		BAD_DATA,
		/** Данные целы, но блок поставить не дали: дисплеи и данные остаются. */
		BLOCKED
	}

	private static RestoreResult restore(ServerLevel level, BlockPos pos) {
		List<Display.TextDisplay> displays = find(level, pos);
		for (Display.TextDisplay display : displays) {
			CompoundTag data = decode(display);
			if (data == null) {
				// битый тег на этом дисплее — пробуем следующий, у обеих сторон данные одинаковые
				continue;
			}
			ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data);
			Optional<BlockState> stored = input.read("state", BlockState.CODEC);
			Optional<CompoundTag> signData = input.read("be", CompoundTag.CODEC);
			// в тег может попасть что угодно (например, /summon с нашим тегом) — ставим только табличку
			if (stored.isEmpty() || signData.isEmpty() || !(stored.get().getBlock() instanceof PlainSignBlock)) {
				GhostFrame.LOGGER.warn("GhostFrame: в данных невидимой таблички на {} не табличка: {}", pos, stored);
				continue;
			}

			if (!level.setBlock(pos, stored.get(), 3)) {
				GhostFrame.LOGGER.warn("GhostFrame: установку таблички отменили на {}", pos);
				return RestoreResult.BLOCKED;
			}
			if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
				// блок уже табличка, но без BE: вернуть данные некуда, дисплеи иначе повиснут навсегда
				GhostFrame.LOGGER.warn("GhostFrame: block entity таблички на {} не создалась", pos);
				displays.forEach(Display.TextDisplay::discard);
				return RestoreResult.BAD_DATA;
			}
			sign.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), signData.get()));
			sign.setChanged();

			displays.forEach(Display.TextDisplay::discard);
			level.playSound(null, pos, SoundEvents.PHANTOM_SWOOP, SoundSource.BLOCKS, 0.8F, 1.4F);
			return RestoreResult.DONE;
		}
		return RestoreResult.BAD_DATA;
	}

	private static void discard(ServerLevel level, BlockPos pos) {
		find(level, pos).forEach(Display.TextDisplay::discard);
	}

	/**
	 * Убирает текст, чей маркер разрушен извне (взрыв, /setblock, пистон, текущая вода). Раньше такой
	 * текст висел навсегда и лечился только ручным {@code /kill @e[type=text_display,tag=ghostframe:sign]}.
	 * Дисплеи с нашим тегом, но без якоря, не трогаем: не знаем, где их маркер.
	 */
	public static void cleanupOrphans(MinecraftServer server) {
		int removed = 0;
		for (ServerLevel level : server.getAllLevels()) {
			List<Display.TextDisplay> orphans = new ArrayList<>();
			for (Entity entity : level.getAllEntities()) {
				if (!(entity instanceof Display.TextDisplay display) || !display.entityTags().contains(TAG_SIGN)
						|| !hasDataTag(display)) {
					// тег легко подделать /summon; без наших данных это не наш призрак
					continue;
				}
				BlockPos anchor = anchorFromTags(display);
				if (anchor == null || !level.isLoaded(anchor)) {
					continue;
				}
				if (!level.getBlockState(anchor).is(Blocks.STRUCTURE_VOID)) {
					orphans.add(display);
				}
			}
			for (Display.TextDisplay display : orphans) {
				display.discard();
				removed++;
			}
		}
		if (removed > 0) {
			GhostFrame.LOGGER.info("GhostFrame: убрано {} осиротевших текстовых дисплеев", removed);
		}
	}

	private static boolean hasDataTag(Display.TextDisplay display) {
		return display.entityTags().stream().anyMatch(tag -> tag.startsWith(TAG_DATA));
	}

	private static BlockPos anchorFromTags(Display.TextDisplay display) {
		for (String tag : display.entityTags()) {
			if (!tag.startsWith(TAG_ANCHOR)) {
				continue;
			}
			String[] parts = tag.substring(TAG_ANCHOR.length()).split(",");
			if (parts.length != 3) {
				return null;
			}
			try {
				return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
						Integer.parseInt(parts[2]));
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	private static CompoundTag decode(Display.TextDisplay display) {
		for (String tag : display.entityTags()) {
			if (!tag.startsWith(TAG_DATA)) {
				continue;
			}
			try {
				byte[] bytes = Base64.getUrlDecoder().decode(tag.substring(TAG_DATA.length()));
				return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
			} catch (IOException | IllegalArgumentException e) {
				GhostFrame.LOGGER.warn("GhostFrame: битые данные невидимой таблички {}", display.getUUID(), e);
			}
		}
		return null;
	}

	/**
	 * Точка дисплея. Для стоячей таблички — центр её текста по формулам
	 * {@code StandingSignRenderer.textTransformation}, сдвинутый вниз так, чтобы низ блока текста
	 * дисплея совпал с низом блока текста таблички. Для настенной — граффити на стене: та же высота,
	 * но вплотную к блоку, на котором табличка висела.
	 */
	static Vec3 displayPos(BlockPos pos, BlockState state, float angle, boolean front) {
		boolean wall = state.getBlock() instanceof WallSignBlock;
		double dy = TEXT_OFFSET_Y + (wall ? WALL_OFFSET_Y : 0.0F) + TEXT_ANCHOR_SHIFT;
		// у настенной доски текст стоит на ней (0.109 от стены), без доски это «висит в воздухе»,
		// поэтому граффити прижимается к самой плоскости стены
		double dz = wall
				? GRAFFITI_DEPTH - 0.5
				: (front ? TEXT_OFFSET_Z : -TEXT_OFFSET_Z);
		double radians = Math.toRadians(-angle);
		return Vec3.atLowerCornerOf(pos)
				.add(0.5 + dz * Math.sin(radians), 0.5 + dy, 0.5 + dz * Math.cos(radians));
	}

	/** Yaw дисплея при {@code billboard: fixed}: у обратной стороны текст повёрнут на 180°. */
	static float displayYaw(float angle, boolean front) {
		return front ? angle : Mth.wrapDegrees(angle + 180.0F);
	}

	/** Угол, которым ваниль поворачивает текст таблички: {@code -angle} в рендерере. */
	static float signAngle(BlockState state) {
		if (state.getBlock() instanceof WallSignBlock) {
			return state.getValue(WallSignBlock.FACING).toYRot();
		}
		return RotationSegment.convertToDegrees(state.getValue(StandingSignBlock.ROTATION));
	}

	/**
	 * Текст дисплея из строк таблички: те же четыре строки (пустые тоже, иначе текст уедет по вертикали),
	 * цвет красителя на каждую строку. У обычных чернил ваниль рисует цвет на 40 % яркости
	 * ({@code AbstractSignRenderer.getDarkColor}), у светящихся — полный.
	 */
	static Component displayText(SignText text) {
		Component[] lines = text.getMessages(false);
		int color = text.hasGlowingText()
				? text.getColor().getTextColor()
				: ARGB.scaleRGB(text.getColor().getTextColor(), 0.4F);
		MutableComponent result = Component.empty();
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				result.append(Component.literal("\n"));
			}
			result.append(lines[i].copy().withStyle(style -> style.withColor(color)));
		}
		return result;
	}

	private static boolean isEmpty(SignText text) {
		for (Component line : text.getMessages(false)) {
			if (!line.getString().isEmpty()) {
				return false;
			}
		}
		return true;
	}

	public static boolean isGhost(Level level, BlockPos pos) {
		return !find(level, pos).isEmpty();
	}

	/** Маркер невидимой таблички: {@code structure_void} в этой клетке, за которым стоит дисплей. */
	public static boolean isGhostMarker(Level level, BlockPos pos) {
		return level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.STRUCTURE_VOID) && isGhost(level, pos);
	}

	private static List<Display.TextDisplay> find(Level level, BlockPos pos) {
		String anchorTag = anchorTag(pos);
		return level.getEntitiesOfClass(Display.TextDisplay.class, new AABB(pos).inflate(2.0),
				display -> display.entityTags().contains(anchorTag));
	}

	private static String anchorTag(BlockPos pos) {
		return TAG_ANCHOR + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static boolean isMembrane(Player player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		return held.is(Items.PHANTOM_MEMBRANE);
	}

	/** Предмет, которым игрок может поставить блок в соседнюю клетку. */
	private static boolean placesBlock(ItemStack stack) {
		return stack.getItem() instanceof BlockItem
				|| stack.getItem() instanceof BucketItem
				|| stack.getItem() instanceof FireChargeItem
				|| stack.getItem() instanceof FlintAndSteelItem
				|| stack.is(Items.FIRE_CHARGE);
	}
}
