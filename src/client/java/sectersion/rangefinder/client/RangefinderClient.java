package sectersion.rangefinder.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;
import java.util.Locale;
import sectersion.rangefinder.client.mixin.OptionsAccessor;
import org.lwjgl.glfw.GLFW;

public class RangefinderClient implements ClientModInitializer {
	private static final double MAX_DISTANCE = 128.0;
	private static KeyMapping measureKey;
	private static BlockHitResult target;
	private static long measurementExpiresAt;
	private static Vec3 measuredStart;
	private static Vec3 measuredEnd;
	private static long measurementStartedAt;
	private static boolean keyRegistered;

	public static BlockHitResult getTarget() {
		return measurementExpiresAt > System.currentTimeMillis() ? target : null;
	}

	@Override
	public void onInitializeClient() {
		measureKey = new KeyMapping(
			"key.rangefinder.measure",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			KeyMapping.Category.MISC
		);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!keyRegistered && client.options != null) {
				KeyMapping[] mappings = client.options.keyMappings;
				((OptionsAccessor) client.options).rangefinder$setKeyMappings(java.util.Arrays.copyOf(mappings, mappings.length + 1));
				client.options.keyMappings[mappings.length] = measureKey;
				KeyMapping.resetMapping();
				keyRegistered = true;
			}
			updateTarget(client);
			renderMeasurement();
			if (measureKey.consumeClick()) {
				showDistance(client);
			}
		});
	}

	private static void updateTarget(Minecraft client) {
		target = null;
		if (client.player == null) return;
		HitResult hit = client.player.pick(MAX_DISTANCE, 0.0F, false);
		if (hit.getType() == HitResult.Type.BLOCK) target = (BlockHitResult) hit;
	}

	private static void showDistance(Minecraft client) {
		if (client.player == null) return;
		if (target == null) {
			client.player.sendSystemMessage(Component.translatable("message.rangefinder.no_block")
				.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			return;
		}
		double distance = client.player.getEyePosition().distanceTo(target.getLocation());
		measurementExpiresAt = System.currentTimeMillis() + 5000L;
		measuredStart = client.player.getEyePosition();
		measuredEnd = target.getLocation();
		measurementStartedAt = System.currentTimeMillis();
		distance = Math.floor(distance * 10.0) / 10.0;
		client.player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "Distance: %.1f blocks", distance))
			.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
	}

	private static void renderMeasurement() {
		if (measuredStart == null || measuredEnd == null) return;
		long remaining = measurementExpiresAt - System.currentTimeMillis();
		if (remaining <= 0) return;

		float progress = Math.min(1.0F, (System.currentTimeMillis() - measurementStartedAt) / 500.0F);
		if (progress <= 0.0F) return;
		Vec3 animatedEnd = measuredStart.lerp(measuredEnd, progress);
		float fade = Math.min(1.0F, remaining / 1000.0F);
		int alpha = (int) (fade * 255.0F);
		int segments = 32;
		for (int i = 0; i < segments; i++) {
			float fromProgress = (float) i / segments;
			float toProgress = (float) (i + 1) / segments;
			Vec3 from = measuredStart.lerp(animatedEnd, fromProgress);
			Vec3 to = measuredStart.lerp(animatedEnd, toProgress);
			Gizmos.line(from, to, gradientColor(fromProgress, alpha), 3.0F).persistForMillis(100);
		}
	}

	private static int gradientColor(float progress, int alpha) {
		int red = (int) (128.0F * (1.0F - progress));
		int blue = 255;
		return alpha << 24 | red << 16 | blue;
	}
}
