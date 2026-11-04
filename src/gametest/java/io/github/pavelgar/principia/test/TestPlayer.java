package io.github.pavelgar.principia.test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * A player in a test's level, in a game mode, its client loaded. The server simulates its movement, as for a player
 * whose client has none, so it falls once nothing holds it up; and it keeps what it is told on the action bar.
 * GameTestHelper's own mock server players either have no connection, which riding and the action bar need, or are
 * always creative and never move by themselves.
 */
final class TestPlayer extends ServerPlayer {
	/** Numbers the test players, whose names must differ. */
	private static final AtomicInteger PLAYERS = new AtomicInteger();

	/** What the player was told on the action bar, in order. */
	final List<Component> overlay = new ArrayList<>();

	private TestPlayer(MinecraftServer server, ServerLevel level, CommonListenerCookie cookie) {
		super(server, level, cookie.gameProfile(), ClientInformation.createDefault());
	}

	/** Puts a player in the test's level, standing at a point and looking along a yaw and a pitch. */
	static TestPlayer place(GameTestHelper helper, GameType gameType, Vec3 feet, float yaw, float pitch) {
		MinecraftServer server = helper.getLevel().getServer();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(
				new GameProfile(UUID.randomUUID(), "test-player-" + PLAYERS.incrementAndGet()), false);
		TestPlayer player = new TestPlayer(server, helper.getLevel(), cookie);
		// Spelled out: inside a ServerPlayer, Connection names the waypoint one it inherits.
		net.minecraft.network.Connection connection = new net.minecraft.network.Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		// As its client does once it has loaded the world: until then the player can't be hurt.
		player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		player.setGameMode(gameType);
		Vec3 at = helper.absoluteVec(feet);
		player.snapTo(at.x, at.y, at.z, yaw, pitch);
		player.setYHeadRot(yaw);
		return player;
	}

	/** Takes players out of the level, as when they log out. */
	static void remove(GameTestHelper helper, TestPlayer... players) {
		for (TestPlayer player : players) {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
	}

	@Override
	public boolean isClientAuthoritative() {
		return false;
	}

	@Override
	public void sendOverlayMessage(Component message) {
		overlay.add(message);
	}
}
