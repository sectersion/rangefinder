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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

public class RangefinderClient implements ClientModInitializer {
	private static final double MAX_DISTANCE = 128.0;
	private static KeyMapping measureKey;
	private static BlockHitResult target;
	private static long measurementExpiresAt;
	private static Vec3 measuredStart;
	private static Vec3 measuredEnd;
	private static long measurementStartedAt;
	private static boolean keyRegistered;
	private static CannonDatabase database;
	private static final ArrayDeque<Double> pendingRecommendations = new ArrayDeque<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static long nextRecommendationAt;

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
			if (database == null) database = loadDatabase(client);
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
			if (database != null && !pendingRecommendations.isEmpty() && System.currentTimeMillis() >= nextRecommendationAt) {
				sendRecommendations(client, pendingRecommendations.removeFirst());
				if (!pendingRecommendations.isEmpty()) nextRecommendationAt = System.currentTimeMillis() + 1000L;
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
		pendingRecommendations.addLast(distance);
		if (pendingRecommendations.size() == 1) nextRecommendationAt = System.currentTimeMillis() + 1000L;
	}

	private static void sendRecommendations(Minecraft client, double distance) {
		if (client.player == null) return;
		List<Cannon> cannons = database.matches(distance);
		if (cannons.isEmpty()) {
			String message = database.isTooClose(distance) ? "Too close!" : database.isTooFar(distance) ? "Too far!" : "No matches found";
			client.player.sendSystemMessage(Component.literal(message)
				.withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
			return;
		}
		client.player.sendSystemMessage(Component.literal("Cannon recommendations:")
			.withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		for (int i = 0; i < cannons.size(); i++) {
			Cannon cannon = cannons.get(i);
			client.player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "%d. %s ", i + 1, cannon.name))
				.withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
				.append(Component.literal(String.format(Locale.ROOT, "%d-%d blocks", cannon.min, cannon.max))
					.withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
		}
	}

	private static CannonDatabase loadDatabase(Minecraft client) {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("rangefinder.json");
		try {
			if (!Files.exists(path)) writeTemplate(path);
			JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			int tolerance = root.get("tolerance").getAsInt();
			JsonArray entries = root.getAsJsonArray("cannons");
			List<Cannon> cannons = new ArrayList<>();
			Set<String> names = new HashSet<>();
			for (var element : entries) {
				JsonObject entry = element.getAsJsonObject();
				String name = entry.get("name").getAsString();
				if (names.add(name)) cannons.add(new Cannon(name, entry.get("minRange").getAsInt(), entry.get("maxRange").getAsInt()));
			}
			return new CannonDatabase(tolerance, cannons);
		} catch (Exception exception) {
			try { Files.deleteIfExists(path); writeTemplate(path); } catch (IOException ignored) { }
			if (client.player != null) client.player.sendSystemMessage(Component.literal("Rangefinder config was reset to its template.").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			return new CannonDatabase(5, List.of(new Cannon("Template Cannon", 40, 50)));
		}
	}

	private static void writeTemplate(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		JsonObject root = new JsonObject();
		root.addProperty("tolerance", 5);
		JsonArray cannons = new JsonArray();
		JsonObject template = new JsonObject();
		template.addProperty("name", "Template Cannon");
		template.addProperty("minRange", 40);
		template.addProperty("maxRange", 50);
		cannons.add(template);
		root.add("cannons", cannons);
		Files.writeString(path, GSON.toJson(root));
	}

	private record Cannon(String name, int min, int max) { }
	private record CannonDatabase(int tolerance, List<Cannon> cannons) {
		List<Cannon> matches(double distance) {
			return cannons.stream().filter(c -> distance >= c.min - tolerance && distance <= c.max + tolerance)
				.sorted((a, b) -> Double.compare(distanceFromRange(distance, a), distanceFromRange(distance, b)))
				.limit(3).toList();
		}
		boolean isTooClose(double distance) {
			return cannons.stream().allMatch(cannon -> distance < cannon.min - tolerance);
		}
		boolean isTooFar(double distance) {
			return cannons.stream().allMatch(cannon -> distance > cannon.max + tolerance);
		}
		private static double distanceFromRange(double distance, Cannon cannon) {
			if (distance < cannon.min) return cannon.min - distance;
			if (distance > cannon.max) return distance - cannon.max;
			return 0;
		}
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
