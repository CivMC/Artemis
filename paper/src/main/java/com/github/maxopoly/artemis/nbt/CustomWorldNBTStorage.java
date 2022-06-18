package com.github.maxopoly.artemis.nbt;

import com.github.maxopoly.artemis.ArtemisPlugin;
import com.github.maxopoly.artemis.rabbit.session.ArtemisPlayerDataTransferSession;
import com.github.maxopoly.artemis.util.BukkitConversion;
import com.github.maxopoly.zeus.ZeusMain;
import com.github.maxopoly.zeus.model.ConnectedMapState;
import com.github.maxopoly.zeus.model.ZeusLocation;
import com.github.maxopoly.zeus.rabbit.outgoing.artemis.SendPlayerData;
import com.github.maxopoly.zeus.rabbit.sessions.PlayerDataTransferSession;
import com.mojang.datafixers.DataFixer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.v1_18_R2.CraftServer;
import vg.civcraft.mc.civmodcore.nbt.wrappers.NBTCompound;
import vg.civcraft.mc.civmodcore.players.settings.PlayerSetting;
import vg.civcraft.mc.civmodcore.players.settings.PlayerSettingAPI;

public class CustomWorldNBTStorage extends PlayerDataStorage {

	private final ExecutorService saveExecutor = Executors.newFixedThreadPool(1);

	private static final String CUSTOM_DATA_ID = "artemis_data";

	private static final Set<UUID> activePlayers = new HashSet<>();
	private Map<UUID, Map<String, String>> customDataOriginallyLoaded;

	public static synchronized void addActivePlayer(UUID uuid) {
		activePlayers.add(uuid);
	}

	public static synchronized void removeActivePlayer(UUID uuid) {
		activePlayers.remove(uuid);
	}

	public static synchronized boolean isActive(UUID uuid) {
		return activePlayers.contains(uuid);
	}

	private final File playerDir;

	private CustomWorldNBTStorage(LevelStorageSource.LevelStorageAccess conversionsession, DataFixer datafixer) {
		super(conversionsession, datafixer);
		this.playerDir = conversionsession.getLevelPath(LevelResource.PLAYER_DATA_DIR).toFile();
		this.playerDir.mkdirs();
		this.customDataOriginallyLoaded = new ConcurrentHashMap<>();
	}

	public void shutdown() {
		activePlayers.clear();
	}

	public void stopExecutor() {
		saveExecutor.shutdown();
	}

	public static ZeusLocation readZeusLocation(byte[] playerData) {
		try {
			CompoundTag nbttagcompound = NbtIo.readCompressed(new ByteArrayInputStream(playerData));
			NBTCompound comp = new NBTCompound(nbttagcompound);
			double[] pos = comp.getDoubleArray("Pos");
			ConnectedMapState mapState = ArtemisPlugin.getInstance().getConfigManager().getConnectedMapState();
			return new ZeusLocation(mapState.getWorld(), pos[0], pos[1], pos[2]);
		} catch (IOException e) {
			ZeusMain.getInstance().getLogger().error("Failed to deserialize nbt", playerData);
			return null;
		}
	}

	public void vanillaSave(Player entityhuman) {
		CompoundTag nbttagcompound = entityhuman.saveWithoutId(new CompoundTag());
		insertCustomPlayerData(entityhuman.getUUID(), nbttagcompound);
		saveFullData(nbttagcompound, entityhuman.getUUID());
	}

	public void saveFullData(CompoundTag compound, UUID uuid) {
		saveExecutor.submit(() -> {
			try {
				File file = File.createTempFile(uuid.toString() + "-", ".dat", this.playerDir);
				NbtIo.writeCompressed(compound, new FileOutputStream(file));
				File file1 = new File(this.playerDir, uuid + ".dat");
				File file2 = new File(this.playerDir, uuid + ".dat_old");
				Util.safeReplaceFile(file1, file, file2);
			} catch (Exception exception) {
				ZeusMain.getInstance().getLogger().warn("Failed to save player data for {}", uuid.toString());
			}
		});

	}

	public void saveFullData(byte[] rawData, UUID uuid) {
		try {
			ByteArrayInputStream input = new ByteArrayInputStream(rawData);
			CompoundTag comp = NbtIo.readCompressed(input);
			saveFullData(comp, uuid);
		} catch (IOException e) {
			ZeusMain.getInstance().getLogger().warn("Failed to save player data for {}", uuid.toString());
		}
	}

	public CompoundTag vanillaLoad(UUID uuid) {
		CompoundTag nbttagcompound = null;
		try {
			File file = new File(this.playerDir, String.valueOf(uuid.toString()) + ".dat");
			if (file.exists() && file.isFile()) {
				nbttagcompound = NbtIo.readCompressed(new FileInputStream(file));
			}
		} catch (Exception exception) {
			ZeusMain.getInstance().getLogger().warn("Failed to vanilla load player data for " + uuid);
		}
		return nbttagcompound;
	}

	public void save(Player entityhuman) {
		if (isActive(entityhuman.getUUID())) {
			vanillaSave(entityhuman);
			return;
		}
		ArtemisPlugin artemis = ArtemisPlugin.getInstance();
		CompoundTag nbttagcompound = entityhuman.saveWithoutId(new CompoundTag());
		insertCustomPlayerData(entityhuman.getUUID(), nbttagcompound);
		if (ArtemisPlugin.getInstance().getConfigManager().isDebugEnabled()) {
			ArtemisPlugin.getInstance().getLogger().info("Saved NBT : " + nbttagcompound.toString());
		}
		String transactionId = ArtemisPlugin.getInstance().getTransactionIdManager().pullNewTicket();
		// create session which will be used to save data locally if Zeus is unavailable
		ArtemisPlayerDataTransferSession session = new ArtemisPlayerDataTransferSession(
				ArtemisPlugin.getInstance().getZeus(), transactionId, entityhuman);
		ArtemisPlugin.getInstance().getTransactionIdManager().putSession(session);
		// save both location and data in that session
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try {
			NbtIo.writeCompressed(nbttagcompound, output);
		} catch (IOException e) {
			artemis.getLogger().severe("Failed to serialize player data: " + e.toString());
			return;
		}
		byte[] data = output.toByteArray();
		ZeusLocation location = new ZeusLocation(artemis.getConfigManager().getWorldName(), entityhuman.locX(),
				entityhuman.locY(), entityhuman.locZ());
		session.setData(data);
		session.setLocation(location);
		// always vanilla save
		vanillaSave(entityhuman);
		ArtemisPlugin.getInstance().getRabbitHandler()
				.sendMessage(new SendPlayerData(transactionId, entityhuman.getUUID(), data, location));
	}

	public CompoundTag load(Player entityhuman) {
		CompoundTag comp = loadCompound(entityhuman.getUUID());
		if (comp != null) {
			int i = comp.contains("DataVersion", 3) ? comp.getInt("DataVersion") : -1;
			entityhuman.load(NbtUtils.update(this.fixerUpper, DataFixTypes.PLAYER, comp, i));
		}
		return comp;
	}

	public CompoundTag getPlayerData(String s) {
		UUID uuid = UUID.fromString(s);
		return loadCompound(uuid);
	}

	private CompoundTag loadCompound(UUID uuid) {
		PlayerDataTransferSession session = ArtemisPlugin.getInstance().getPlayerDataCache().consumeSession(uuid);
		if (session == null) {
			return null;
		}
		if (session.getData().length == 0) {
			// new player, data will be generated
			return null;
		}
		ByteArrayInputStream input = new ByteArrayInputStream(session.getData());
		try {
			NBTCompound comp = new NBTCompound(NbtIo.readCompressed(input));
			ZeusLocation loc = session.getLocation();
			if (loc == null) {
				loc = BukkitConversion.convertLocation(
						ArtemisPlugin.getInstance().getRandomSpawnHandler().getRandomSpawnLocation(uuid));
			}
			if (loc != null) {
				comp.setDoubleArray("Pos", new double[] { loc.getX(), loc.getY(), loc.getZ() });
			}
			insertWorldUUID(comp);
			if (comp.hasKeyOfType(CUSTOM_DATA_ID, 10)) {
				NBTCompound customData = comp.getCompound(CUSTOM_DATA_ID);
				extractCustomPlayerData(uuid, customData);
			}
			if (ArtemisPlugin.getInstance().getConfigManager().isDebugEnabled()) {
				ArtemisPlugin.getInstance().getLogger().info("Loaded NBT : " + comp.toString());
			}
			return comp.getRAW();
		} catch (IOException e) {
			ArtemisPlugin.getInstance().getLogger().log(Level.SEVERE, "Failed to load player data", e);
			return null;
		}
	}

	private static void insertWorldUUID(NBTCompound compound) {
		String worldName = ArtemisPlugin.getInstance().getConfigManager().getConnectedMapState().getWorld();
		UUID worldUUID = Bukkit.getWorld(worldName).getUID();
		compound.setLong("WorldUUIDLeast", worldUUID.getLeastSignificantBits());
		compound.setLong("WorldUUIDMost", worldUUID.getMostSignificantBits());
	}

	public static CustomWorldNBTStorage insertCustomNBTHandler() {
		Server server = Bukkit.getServer();
		try {
			Field trueServerField = CraftServer.class.getDeclaredField("console");
			trueServerField.setAccessible(true);
			MinecraftServer trueServer = (MinecraftServer) trueServerField.get(server);
			Field nbtField = MinecraftServer.class.getDeclaredField("playerDataStorage");
			LevelStorageSource.LevelStorageAccess session = trueServer.storageSource;
			DataFixer dataFixer = trueServer.fixerUpper;
			CustomWorldNBTStorage customNBT = new CustomWorldNBTStorage(session, dataFixer);
			overwriteFinalField(nbtField, customNBT, trueServer);
			Field playerListField = CraftServer.class.getDeclaredField("playerList");
			playerListField.setAccessible(true);
			DedicatedPlayerList playerList = (DedicatedPlayerList) playerListField.get(server);
			Field nbtPlayerListField = PlayerList.class.getField("playerIo");
			overwriteFinalField(nbtPlayerListField, customNBT, playerList);
			return customNBT;
		} catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
			ArtemisPlugin.getInstance().getLogger().log(Level.SEVERE, "Failed to set custom nbt handler", e);
			return null;
		}
	}

	private void extractCustomPlayerData(UUID player, NBTCompound specialDataCompound) {
		// we keep data in this map so settings not loaded on the server currently are
		// not reset
		Map<String, String> extractedData = new HashMap<>();
		for (PlayerSetting setting : PlayerSettingAPI.getAllSettings()) {
			if (!specialDataCompound.hasKey(setting.getIdentifier())) {
				continue;
			}
			String serial = specialDataCompound.getString(setting.getIdentifier());
			extractedData.put(setting.getIdentifier(), serial);
			try {
				Object deserialized = setting.deserialize(serial);
				setting.setValueInternal(player, deserialized);
			} catch(Exception e) {
				//otherwise bad data prevents login entirely
				ArtemisPlugin.getInstance().getLogger().log(Level.SEVERE, 
						"Failed to parse player setting " + setting.getIdentifier(), e);
			}
		}
		this.customDataOriginallyLoaded.put(player, extractedData);
	}

	private void insertCustomPlayerData(UUID player, CompoundTag generalPlayerDataCompound) {
		Map<String, String> dataToInsert = customDataOriginallyLoaded.computeIfAbsent(player, p -> new HashMap<>());
		for (PlayerSetting setting : PlayerSettingAPI.getAllSettings()) {
			if (!setting.hasValue(player)) {
				continue;
			}
			String serial = setting.serialize(setting.getValue(player));
			dataToInsert.put(setting.getIdentifier(), serial);
		}
		NBTCompound comp = new NBTCompound(generalPlayerDataCompound);
		NBTCompound customDataComp = new NBTCompound();
		for (Entry<String, String> entry : dataToInsert.entrySet()) {
			customDataComp.setString(entry.getKey(), entry.getValue());
		}
		comp.setCompound(CUSTOM_DATA_ID, customDataComp);
	}

	private static void overwriteFinalField(Field field, Object newValue, Object obj) {
		try {
			field.setAccessible(true);
			// remove final modifier from field
			Field modifiersField;
			modifiersField = Field.class.getDeclaredField("modifiers");
			modifiersField.setAccessible(true);
			modifiersField.setInt(field, field.getModifiers() & ~Modifier.PROTECTED);
			field.set(obj, newValue);
		} catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
			ArtemisPlugin.getInstance().getLogger().log(Level.SEVERE, "Failed to set final field", e);
		}
	}

}
